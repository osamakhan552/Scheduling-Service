package com.example.scheduler.service;

import com.example.scheduler.domain.InvalidAppointmentException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AppointmentInputValidator {
    // PostgreSQL stores microseconds; reject more precise inputs instead of rounding them.
    private static final Pattern UTC_INSTANT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z");

    public BookingInput booking(String providerId, String startTime, String endTime) {
        UUID provider = parseUuid(providerId, "INVALID_REQUEST", "providerId must be a UUID.");
        Instant start = parseInstant(startTime);
        Instant end = parseInstant(endTime);
        if (!start.isBefore(end)) {
            throw new InvalidAppointmentException("INVALID_INTERVAL",
                    "endTime must be after startTime.");
        }
        return new BookingInput(provider, start, end);
    }

    public UUID cancellationId(String appointmentId) {
        return parseUuid(appointmentId, "INVALID_APPOINTMENT_ID",
                "Appointment id must be a UUID.");
    }

    private UUID parseUuid(String raw, String code, String message) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAppointmentException(code, message);
        }
        try {
            // A UUID must use the full canonical 8-4-4-4-12 spelling.
            UUID id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) {
                throw new IllegalArgumentException("Noncanonical UUID");
            }
            return id;
        } catch (IllegalArgumentException ex) {
            throw new InvalidAppointmentException(code, message);
        }
    }

    private Instant parseInstant(String raw) {
        if (raw == null || !UTC_INSTANT.matcher(raw).matches()) {
            throw new InvalidAppointmentException("INVALID_TIMESTAMP",
                    "Timestamps must be ISO-8601 UTC instants ending in Z with at most six fractional digits.");
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new InvalidAppointmentException("INVALID_TIMESTAMP", "Invalid UTC timestamp.");
        }
    }

    public record BookingInput(UUID providerId, Instant startTime, Instant endTime) {
    }
}
