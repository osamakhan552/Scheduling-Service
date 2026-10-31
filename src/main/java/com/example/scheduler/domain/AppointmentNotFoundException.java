package com.example.scheduler.domain;

public final class AppointmentNotFoundException extends SchedulerException {
    public AppointmentNotFoundException() {
        super("APPOINTMENT_NOT_FOUND", "Appointment was not found.");
    }
}
