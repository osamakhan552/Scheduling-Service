package com.example.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "appointments")
public class Appointment {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "start_time", nullable = false, updatable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppointmentStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Appointment() {
        // Required by JPA.
    }

    private Appointment(UUID id, UUID providerId, Instant startTime,
                        Instant endTime, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.providerId = Objects.requireNonNull(providerId);
        this.startTime = Objects.requireNonNull(startTime);
        this.endTime = Objects.requireNonNull(endTime);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("End must be after start");
        }
        this.status = AppointmentStatus.ACTIVE;
    }

    public static Appointment create(UUID providerId, Instant startTime,
                                     Instant endTime, Instant createdAt) {
        return new Appointment(UUID.randomUUID(), providerId, startTime, endTime, createdAt);
    }

    public void cancel(Instant now) {
        if (status != AppointmentStatus.ACTIVE) {
            throw new AlreadyCancelledException();
        }
        status = AppointmentStatus.CANCELLED;
        cancelledAt = Objects.requireNonNull(now);
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public AppointmentDetails details() {
        return new AppointmentDetails(id, providerId, startTime, endTime,
                status, createdAt, cancelledAt);
    }
}
