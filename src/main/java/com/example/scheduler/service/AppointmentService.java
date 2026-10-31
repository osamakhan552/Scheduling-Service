package com.example.scheduler.service;

import com.example.scheduler.domain.AlreadyCancelledException;
import com.example.scheduler.domain.Appointment;
import com.example.scheduler.domain.AppointmentConflictException;
import com.example.scheduler.domain.AppointmentDetails;
import com.example.scheduler.domain.AppointmentNotFoundException;
import com.example.scheduler.domain.AppointmentStatus;
import com.example.scheduler.domain.ConcurrentAppointmentModificationException;
import com.example.scheduler.repository.AppointmentRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AppointmentService {
    private static final String EXCLUSION_CONSTRAINT = "appointments_no_active_overlap";

    private final AppointmentRepository repository;
    private final AppointmentInputValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AppointmentService(AppointmentRepository repository, AppointmentInputValidator validator,
                              Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AppointmentDetails book(String providerId, String startTime, String endTime) {
        // Complete validation before beginning a transaction or making a repository call.
        AppointmentInputValidator.BookingInput input = validator.booking(providerId, startTime, endTime);
        try {
            return transactions.execute(status -> {
                if (repository.hasActiveOverlap(input.providerId(), input.startTime(), input.endTime())) {
                    throw new AppointmentConflictException();
                }
                Appointment appointment = Appointment.create(input.providerId(),
                        input.startTime(), input.endTime(), Instant.now(clock));
                // Force PostgreSQL to evaluate the exclusion constraint before returning.
                repository.saveAndFlush(appointment);
                return appointment.details();
            });
        } catch (RuntimeException ex) {
            // The transaction has ended (rolled back) before classifying the SQL failure.
            if (isBookingExclusionViolation(ex)) {
                throw new AppointmentConflictException();
            }
            throw ex;
        }
    }

    public AppointmentDetails cancel(String appointmentId) {
        UUID id = validator.cancellationId(appointmentId);
        try {
            return transactions.execute(status -> {
                Appointment appointment = repository.findById(id)
                        .orElseThrow(AppointmentNotFoundException::new);
                if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
                    throw new AlreadyCancelledException();
                }
                appointment.cancel(Instant.now(clock));
                repository.flush(); // Version check occurs before transaction completes.
                return appointment.details();
            });
        } catch (RuntimeException ex) {
            if (!isOptimisticFailure(ex)) {
                throw ex;
            }
            // Do not query the aborted transaction. Read fresh state in a new transaction.
            AppointmentStatus current = transactions.execute(status -> repository.findById(id)
                    .map(Appointment::getStatus)
                    .orElse(null));
            if (current == null) {
                throw new AppointmentNotFoundException();
            }
            if (current == AppointmentStatus.CANCELLED) {
                throw new AlreadyCancelledException();
            }
            throw new ConcurrentAppointmentModificationException();
        }
    }

    private static boolean isBookingExclusionViolation(Throwable error) {
        boolean sqlStateMatched = false;
        String constraintName = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                sqlStateMatched = true;
            }
            if (cause instanceof ConstraintViolationException violation) {
                constraintName = violation.getConstraintName();
            }
        }
        // The name, when available, must match our specific booking constraint.
        return sqlStateMatched && (constraintName == null || EXCLUSION_CONSTRAINT.equals(constraintName));
    }

    private static boolean isOptimisticFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException
                    || cause instanceof StaleObjectStateException) {
                return true;
            }
        }
        return false;
    }
}
