package com.example.scheduler.api;

import com.example.scheduler.domain.AppointmentDetails;
import com.example.scheduler.domain.AppointmentStatus;
import java.time.Instant;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        UUID providerId,
        Instant startTime,
        Instant endTime,
        AppointmentStatus status,
        Instant createdAt,
        Instant cancelledAt) {
    public static AppointmentResponse from(AppointmentDetails details) {
        return new AppointmentResponse(details.id(), details.providerId(),
                details.startTime(), details.endTime(), details.status(),
                details.createdAt(), details.cancelledAt());
    }
}
