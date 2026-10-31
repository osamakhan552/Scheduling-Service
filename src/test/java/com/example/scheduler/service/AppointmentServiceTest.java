package com.example.scheduler.service;

import com.example.scheduler.domain.AlreadyCancelledException;
import com.example.scheduler.domain.Appointment;
import com.example.scheduler.domain.AppointmentConflictException;
import com.example.scheduler.domain.AppointmentDetails;
import com.example.scheduler.domain.AppointmentNotFoundException;
import com.example.scheduler.domain.AppointmentStatus;
import com.example.scheduler.domain.ConcurrentAppointmentModificationException;
import com.example.scheduler.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock private AppointmentRepository repository;
    @Mock private PlatformTransactionManager transactionManager;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-10-31T06:00:00Z"), ZoneOffset.UTC);
    private final AppointmentInputValidator validator = new AppointmentInputValidator();

    private AppointmentService service;

    private static final String PROVIDER_ID  = "550e8400-e29b-41d4-a716-446655440000";
    private static final String START_TIME   = "2026-11-01T10:00:00Z";
    private static final String END_TIME     = "2026-11-01T11:00:00Z";

    @BeforeEach
    void setUp() {
        // Make the transaction manager execute lambdas synchronously (no real DB).
        // lenient() avoids UnnecessaryStubbingException for tests that don't trigger commit/rollback.
        TransactionStatus status = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        lenient().doNothing().when(transactionManager).commit(status);
        lenient().doNothing().when(transactionManager).rollback(status);

        service = new AppointmentService(repository, validator, fixedClock, transactionManager);
    }

    // ── book() ─────────────────────────────────────────────────────────────────

    @Test
    void book_noOverlap_savesAndReturnsDetails() {
        when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        AppointmentDetails result = service.book(PROVIDER_ID, START_TIME, END_TIME);

        assertThat(result.status()).isEqualTo(AppointmentStatus.ACTIVE);
        assertThat(result.providerId()).isEqualTo(UUID.fromString(PROVIDER_ID));
        assertThat(result.startTime()).isEqualTo(Instant.parse(START_TIME));
        assertThat(result.endTime()).isEqualTo(Instant.parse(END_TIME));
        assertThat(result.createdAt()).isEqualTo(fixedClock.instant());
        assertThat(result.cancelledAt()).isNull();
        verify(repository).saveAndFlush(any(Appointment.class));
    }

    @Test
    void book_overlapDetectedByQuery_throwsConflict() {
        when(repository.hasActiveOverlap(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.book(PROVIDER_ID, START_TIME, END_TIME))
                .isInstanceOf(AppointmentConflictException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void book_invalidProviderId_throwsBeforeTransaction() {
        assertThatThrownBy(() -> service.book("bad-id", START_TIME, END_TIME))
                .isInstanceOf(com.example.scheduler.domain.InvalidAppointmentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void book_endBeforeStart_throwsBeforeTransaction() {
        assertThatThrownBy(() -> service.book(PROVIDER_ID, END_TIME, START_TIME))
                .isInstanceOf(com.example.scheduler.domain.InvalidAppointmentException.class);

        verifyNoInteractions(repository);
    }

    // ── cancel() ───────────────────────────────────────────────────────────────

    @Test
    void cancel_activeAppointment_cancelsAndReturnsDetails() {
        UUID id = UUID.randomUUID();
        Appointment appointment = Appointment.create(
                UUID.fromString(PROVIDER_ID),
                Instant.parse(START_TIME), Instant.parse(END_TIME),
                fixedClock.instant());

        when(repository.findById(id)).thenReturn(Optional.of(appointment));
        doNothing().when(repository).flush();

        AppointmentDetails result = service.cancel(id.toString());

        assertThat(result.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(result.cancelledAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    void cancel_notFound_throwsNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(id.toString()))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void cancel_alreadyCancelled_throwsAlreadyCancelledException() {
        UUID id = UUID.randomUUID();
        Appointment appointment = Appointment.create(
                UUID.fromString(PROVIDER_ID),
                Instant.parse(START_TIME), Instant.parse(END_TIME),
                fixedClock.instant());
        appointment.cancel(fixedClock.instant());

        when(repository.findById(id)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> service.cancel(id.toString()))
                .isInstanceOf(AlreadyCancelledException.class);
    }

    @Test
    void cancel_optimisticLockFailure_andCurrentlyCancelled_throwsAlreadyCancelled() {
        UUID id = UUID.randomUUID();
        Appointment cancelledAppointment = Appointment.create(
                UUID.fromString(PROVIDER_ID),
                Instant.parse(START_TIME), Instant.parse(END_TIME),
                fixedClock.instant());
        cancelledAppointment.cancel(fixedClock.instant());

        // First call throws optimistic lock; second (recovery) returns cancelled.
        when(repository.findById(id))
                .thenReturn(Optional.of(Appointment.create(
                        UUID.fromString(PROVIDER_ID),
                        Instant.parse(START_TIME), Instant.parse(END_TIME),
                        fixedClock.instant())))
                .thenReturn(Optional.of(cancelledAppointment));
        doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

        assertThatThrownBy(() -> service.cancel(id.toString()))
                .isInstanceOf(AlreadyCancelledException.class);
    }

    @Test
    void cancel_optimisticLockFailure_andAppointmentDisappeared_throwsNotFound() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id))
                .thenReturn(Optional.of(Appointment.create(
                        UUID.fromString(PROVIDER_ID),
                        Instant.parse(START_TIME), Instant.parse(END_TIME),
                        fixedClock.instant())))
                .thenReturn(Optional.empty()); // Gone after rollback
        doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

        assertThatThrownBy(() -> service.cancel(id.toString()))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    @Test
    void cancel_optimisticLockFailure_andStillActive_throwsConcurrentModification() {
        UUID id = UUID.randomUUID();

        // Return a fresh ACTIVE appointment each time (different object instances).
        when(repository.findById(id))
                .thenAnswer(inv -> Optional.of(Appointment.create(
                        UUID.fromString(PROVIDER_ID),
                        Instant.parse(START_TIME), Instant.parse(END_TIME),
                        fixedClock.instant())))
                .thenAnswer(inv -> Optional.of(Appointment.create(
                        UUID.fromString(PROVIDER_ID),
                        Instant.parse(START_TIME), Instant.parse(END_TIME),
                        fixedClock.instant())));
        doThrow(new OptimisticLockingFailureException("stale")).when(repository).flush();

        assertThatThrownBy(() -> service.cancel(id.toString()))
                .isInstanceOf(ConcurrentAppointmentModificationException.class);
    }

    @Test
    void cancel_invalidId_throwsBeforeTransaction() {
        assertThatThrownBy(() -> service.cancel("not-a-uuid"))
                .isInstanceOf(com.example.scheduler.domain.InvalidAppointmentException.class);

        verifyNoInteractions(repository);
    }
}
