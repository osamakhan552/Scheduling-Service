package com.example.scheduler.service;

import com.example.scheduler.domain.InvalidAppointmentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentInputValidatorTest {

    private final AppointmentInputValidator validator = new AppointmentInputValidator();

    // ── booking() ──────────────────────────────────────────────────────────────

    @Test
    void booking_validInput_returnsBookingInput() {
        String providerId = UUID.randomUUID().toString();
        AppointmentInputValidator.BookingInput input =
                validator.booking(providerId, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");

        assertThat(input.providerId()).isEqualTo(UUID.fromString(providerId));
        assertThat(input.startTime()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
        assertThat(input.endTime()).isEqualTo(Instant.parse("2026-11-01T11:00:00Z"));
    }

    @Test
    void booking_withMicroseconds_accepted() {
        String providerId = UUID.randomUUID().toString();
        AppointmentInputValidator.BookingInput input =
                validator.booking(providerId, "2026-11-01T10:00:00.123456Z", "2026-11-01T11:00:00Z");

        assertThat(input.startTime()).isEqualTo(Instant.parse("2026-11-01T10:00:00.123456Z"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-uuid", "12345", "550e8400e29b41d4a716446655440000"})
    void booking_invalidProviderId_throwsInvalidAppointmentException(String badId) {
        assertThatThrownBy(() -> validator.booking(badId, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"))
                .isInstanceOf(InvalidAppointmentException.class)
                .hasMessageContaining("providerId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "2026-11-01T10:00:00",        // missing Z
            "2026-11-01 10:00:00Z",       // space instead of T
            "2026-11-01T10:00:00.1234567Z", // 7 fractional digits
            "not-a-timestamp"
    })
    void booking_invalidStartTime_throwsInvalidAppointmentException(String badTime) {
        String providerId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> validator.booking(providerId, badTime, "2026-11-01T11:00:00Z"))
                .isInstanceOf(InvalidAppointmentException.class)
                .extracting(e -> ((InvalidAppointmentException) e).code())
                .isEqualTo("INVALID_TIMESTAMP");
    }

    @Test
    void booking_endBeforeStart_throwsInvalidInterval() {
        String providerId = UUID.randomUUID().toString();
        assertThatThrownBy(() ->
                validator.booking(providerId, "2026-11-01T11:00:00Z", "2026-11-01T10:00:00Z"))
                .isInstanceOf(InvalidAppointmentException.class)
                .extracting(e -> ((InvalidAppointmentException) e).code())
                .isEqualTo("INVALID_INTERVAL");
    }

    @Test
    void booking_endEqualsStart_throwsInvalidInterval() {
        String providerId = UUID.randomUUID().toString();
        assertThatThrownBy(() ->
                validator.booking(providerId, "2026-11-01T10:00:00Z", "2026-11-01T10:00:00Z"))
                .isInstanceOf(InvalidAppointmentException.class)
                .extracting(e -> ((InvalidAppointmentException) e).code())
                .isEqualTo("INVALID_INTERVAL");
    }

    // ── cancellationId() ───────────────────────────────────────────────────────

    @Test
    void cancellationId_validUuid_returnsParsedUuid() {
        UUID id = UUID.randomUUID();
        assertThat(validator.cancellationId(id.toString())).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-uuid", "abc"})
    void cancellationId_invalid_throwsInvalidAppointmentException(String badId) {
        assertThatThrownBy(() -> validator.cancellationId(badId))
                .isInstanceOf(InvalidAppointmentException.class)
                .extracting(e -> ((InvalidAppointmentException) e).code())
                .isEqualTo("INVALID_APPOINTMENT_ID");
    }
}
