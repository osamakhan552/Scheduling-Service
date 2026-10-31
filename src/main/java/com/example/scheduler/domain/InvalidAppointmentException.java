package com.example.scheduler.domain;

public final class InvalidAppointmentException extends SchedulerException {
    public InvalidAppointmentException(String code, String message) {
        super(code, message);
    }
}
