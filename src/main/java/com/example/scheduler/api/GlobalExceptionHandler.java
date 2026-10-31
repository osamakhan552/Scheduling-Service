package com.example.scheduler.api;

import com.example.scheduler.domain.AlreadyCancelledException;
import com.example.scheduler.domain.AppointmentConflictException;
import com.example.scheduler.domain.AppointmentNotFoundException;
import com.example.scheduler.domain.ConcurrentAppointmentModificationException;
import com.example.scheduler.domain.InvalidAppointmentException;
import com.example.scheduler.domain.SchedulerException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SchedulerException.class)
    public ResponseEntity<ProblemDetail> handleDomain(SchedulerException ex, HttpServletRequest request) {
        HttpStatus status;
        if (ex instanceof InvalidAppointmentException) {
            status = HttpStatus.BAD_REQUEST;
        } else if (ex instanceof AppointmentNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (ex instanceof AppointmentConflictException
                || ex instanceof AlreadyCancelledException
                || ex instanceof ConcurrentAppointmentModificationException) {
            status = HttpStatus.CONFLICT;
        } else {
            throw ex;
        }
        return problem(status, ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Required request fields must be non-blank.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Request body must be valid JSON with required fields.", request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code,
                                                         String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        return ResponseEntity.status(status)
                .header("Content-Type", "application/problem+json")
                .body(body);
    }
}
