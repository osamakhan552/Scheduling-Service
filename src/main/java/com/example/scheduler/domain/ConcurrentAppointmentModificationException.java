package com.example.scheduler.domain;

public final class ConcurrentAppointmentModificationException extends SchedulerException {
    public ConcurrentAppointmentModificationException() {
        super("CONCURRENT_MODIFICATION", "Appointment changed concurrently; please retry.");
    }
}
