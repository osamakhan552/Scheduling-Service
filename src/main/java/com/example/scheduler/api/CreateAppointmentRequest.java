package com.example.scheduler.api;

import jakarta.validation.constraints.NotBlank;

public record CreateAppointmentRequest(
        @NotBlank String providerId,
        @NotBlank String startTime,
        @NotBlank String endTime) {
}
