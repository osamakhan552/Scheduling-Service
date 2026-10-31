package com.example.scheduler.domain;

public abstract class SchedulerException extends RuntimeException {
    private final String code;

    protected SchedulerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
