package com.example.scheduler.domain;

import java.time.Instant;
import java.util.UUID;

public record AppointmentDetails(
        UUID id,
        UUID providerId,
        Instant startTime,
        Instant endTime,
        AppointmentStatus status,
        Instant createdAt,
        Instant cancelledAt) {
}
