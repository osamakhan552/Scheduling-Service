package com.example.scheduler.domain;

public final class AppointmentConflictException extends SchedulerException {
    public AppointmentConflictException() {
        super("APPOINTMENT_CONFLICT",
                "Provider already has an active appointment overlapping the requested interval.");
    }
}
