package com.example.scheduler.domain;

public final class AlreadyCancelledException extends SchedulerException {
    public AlreadyCancelledException() {
        super("ALREADY_CANCELLED", "Appointment is already cancelled.");
    }
}
