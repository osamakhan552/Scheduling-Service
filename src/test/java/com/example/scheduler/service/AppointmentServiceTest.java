package com.example.scheduler.service;

import com.example.scheduler.domain.AlreadyCancelledException;
import com.example.scheduler.domain.Appointment;
import com.example.scheduler.domain.AppointmentConflictException;
import com.example.scheduler.domain.AppointmentDetails;
import com.example.scheduler.domain.AppointmentNotFoundException;
import com.example.scheduler.domain.AppointmentStatus;
import com.example.scheduler.domain.ConcurrentAppointmentModificationException;
import com.example.scheduler.domain.InvalidAppointmentException;
import com.example.scheduler.repository.AppointmentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AppointmentService}.
 *
 * The {@link PlatformTransactionManager} is mocked to execute lambdas
 * synchronously so no database is needed.  Overlap logic is tested both
 * via the early hasActiveOverlap guard and via the exclusion-constraint
 * exception path that catches concurrent race conditions.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock private AppointmentRepository repository;
    @Mock private PlatformTransactionManager txManager;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T06:00:00Z"), ZoneOffset.UTC);
    private static final String PROVIDER = "550e8400-e29b-41d4-a716-446655440000";
    private static final String START    = "2026-11-01T10:00:00Z";
    private static final String END      = "2026-11-01T11:00:00Z";

    private AppointmentService service;

    @BeforeEach
    void setUp() {
        // Wire a synchronous, no-op transaction manager so lambdas run inline.
        // lenient() suppresses UnnecessaryStubbingException for tests that
        // short-circuit before commit/rollback (e.g., validation-only tests).
        TransactionStatus status = new SimpleTransactionStatus();
        lenient().when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        lenient().doNothing().when(txManager).commit(status);
        lenient().doNothing().when(txManager).rollback(status);

        service = new AppointmentService(repository, new AppointmentInputValidator(), CLOCK, txManager);
    }

    // ── book() ─────────────────────────────────────────────────────────────────

    @Nested
    class Book {

        @Test
        void noOverlap_savesAppointment_returnsActiveDetails() {
            when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(false);
            when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            AppointmentDetails result = service.book(PROVIDER, START, END);

            assertThat(result.status()).isEqualTo(AppointmentStatus.ACTIVE);
            assertThat(result.providerId()).isEqualTo(UUID.fromString(PROVIDER));
            assertThat(result.startTime()).isEqualTo(Instant.parse(START));
            assertThat(result.endTime()).isEqualTo(Instant.parse(END));
            assertThat(result.createdAt()).isEqualTo(CLOCK.instant());
            assertThat(result.cancelledAt()).isNull();
            verify(repository).saveAndFlush(any(Appointment.class));
        }

        // ── Overlap detection — early-read guard path ──────────────────────────

        @Test
        void overlap_earlyGuardFires_throwsConflict_noSave() {
            // hasActiveOverlap returns true → conflict detected before even trying to save
            when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.book(PROVIDER, START, END))
                    .isInstanceOf(AppointmentConflictException.class)
                    .satisfies(ex -> assertThat(((AppointmentConflictException) ex).code())
                            .isEqualTo("APPOINTMENT_CONFLICT"));

            verify(repository, never()).saveAndFlush(any());
        }

        // ── Overlap detection — exclusion constraint path (race condition) ─────

        @Test
        void overlap_exclusionConstraintFired_translatedToConflict() {
            // Early guard passes (no overlap visible yet), but two concurrent
            // requests race; the DB exclusion constraint rejects the second one.
            when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(false);

            SQLException sqlEx = new SQLException("exclusion constraint violated", "23P01");
            ConstraintViolationException cve = new ConstraintViolationException(
                    "conflict", sqlEx, "appointments_no_active_overlap");
            when(repository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("constraint", cve));

            assertThatThrownBy(() -> service.book(PROVIDER, START, END))
                    .isInstanceOf(AppointmentConflictException.class);
        }

        @Test
        void overlap_exclusionConstraintWithNullName_translatedToConflict() {
            // Constraint name may be null on some JDBC drivers; code must still match on SQLState alone.
            when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(false);

            SQLException sqlEx = new SQLException("exclusion constraint violated", "23P01");
            ConstraintViolationException cve = new ConstraintViolationException("conflict", sqlEx, null);
            when(repository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("constraint", cve));

            assertThatThrownBy(() -> service.book(PROVIDER, START, END))
                    .isInstanceOf(AppointmentConflictException.class);
        }

        @Test
        void overlap_unrelatedConstraintViolation_rethrown() {
            // SQLState 23505 is a unique violation — unrelated to our exclusion constraint.
            when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(false);

            SQLException sqlEx = new SQLException("unique violation", "23505");
            ConstraintViolationException cve = new ConstraintViolationException("other", sqlEx, "some_other_constraint");
            RuntimeException ex = new DataIntegrityViolationException("other constraint", cve);
            when(repository.saveAndFlush(any())).thenThrow(ex);

            assertThatThrownBy(() -> service.book(PROVIDER, START, END))
                    .isSameAs(ex); // must NOT be translated to AppointmentConflictException
        }

        // ── Validation fires before any repository call ────────────────────────

        @Test
        void invalidProviderId_throwsBeforeRepository() {
            assertThatThrownBy(() -> service.book("bad-id", START, END))
                    .isInstanceOf(InvalidAppointmentException.class);
            verifyNoInteractions(repository);
        }

        @Test
        void endBeforeStart_throwsBeforeRepository() {
            assertThatThrownBy(() -> service.book(PROVIDER, END, START))
                    .isInstanceOf(InvalidAppointmentException.class);
            verifyNoInteractions(repository);
        }

        @Test
        void endEqualsStart_throwsBeforeRepository() {
            assertThatThrownBy(() -> service.book(PROVIDER, START, START))
                    .isInstanceOf(InvalidAppointmentException.class);
            verifyNoInteractions(repository);
        }
    }

    // ── cancel() ───────────────────────────────────────────────────────────────

    @Nested
    class Cancel {

        @Test
        void activeAppointment_cancelledSuccessfully_returnsDetails() {
            UUID id = UUID.randomUUID();
            Appointment appt = freshActive();
            when(repository.findById(id)).thenReturn(Optional.of(appt));
            doNothing().when(repository).flush();

            AppointmentDetails result = service.cancel(id.toString());

            assertThat(result.status()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(result.cancelledAt()).isEqualTo(CLOCK.instant());
        }

        @Test
        void missingAppointment_throwsNotFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(id.toString()))
                    .isInstanceOf(AppointmentNotFoundException.class);
        }

        @Test
        void alreadyCancelledAppointment_throwsAlreadyCancelled() {
            UUID id = UUID.randomUUID();
            Appointment appt = freshActive();
            appt.cancel(CLOCK.instant());
            when(repository.findById(id)).thenReturn(Optional.of(appt));

            assertThatThrownBy(() -> service.cancel(id.toString()))
                    .isInstanceOf(AlreadyCancelledException.class);
        }

        // ── Optimistic-lock recovery paths ─────────────────────────────────────

        @Test
        void optimisticLock_recoveryReadShowsCancelled_throwsAlreadyCancelled() {
            UUID id = UUID.randomUUID();
            Appointment cancelledAppt = freshActive();
            cancelledAppt.cancel(CLOCK.instant());

            when(repository.findById(id))
                    .thenReturn(Optional.of(freshActive()))      // first read — ACTIVE
                    .thenReturn(Optional.of(cancelledAppt));     // recovery read — CANCELLED
            doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

            assertThatThrownBy(() -> service.cancel(id.toString()))
                    .isInstanceOf(AlreadyCancelledException.class);
        }

        @Test
        void optimisticLock_recoveryReadShowsGone_throwsNotFound() {
            UUID id = UUID.randomUUID();

            when(repository.findById(id))
                    .thenReturn(Optional.of(freshActive()))  // first read
                    .thenReturn(Optional.empty());           // recovery read — row gone
            doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

            assertThatThrownBy(() -> service.cancel(id.toString()))
                    .isInstanceOf(AppointmentNotFoundException.class);
        }

        @Test
        void optimisticLock_recoveryReadShowsStillActive_throwsConcurrentModification() {
            UUID id = UUID.randomUUID();

            // Both reads must return independent instances; the first instance gets
            // mutated by cancel() before the flush throws, so the second must be fresh.
            when(repository.findById(id))
                    .thenAnswer(inv -> Optional.of(freshActive()))
                    .thenAnswer(inv -> Optional.of(freshActive()));
            doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

            assertThatThrownBy(() -> service.cancel(id.toString()))
                    .isInstanceOf(ConcurrentAppointmentModificationException.class);
        }

        // ── Validation fires before any repository call ────────────────────────

        @Test
        void invalidId_throwsBeforeRepository() {
            assertThatThrownBy(() -> service.cancel("not-a-uuid"))
                    .isInstanceOf(InvalidAppointmentException.class);
            verifyNoInteractions(repository);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Appointment freshActive() {
        return Appointment.create(
                UUID.fromString(PROVIDER),
                Instant.parse(START), Instant.parse(END),
                CLOCK.instant());
    }
}
