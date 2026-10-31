package com.example.scheduler.service;

import com.example.scheduler.domain.InvalidAppointmentException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Pure unit tests for {@link AppointmentInputValidator} — no Spring context needed.
 *
 * Table-driven cases use {@code @MethodSource} so new rows can be added
 * without touching test methods.
 */
class AppointmentInputValidatorTest {

    private final AppointmentInputValidator validator = new AppointmentInputValidator();

    // ── booking() — valid inputs ───────────────────────────────────────────────

    @Nested
    class ValidBooking {

        @Test
        void allFields_valid_returnsPopulatedRecord() {
            String pid = UUID.randomUUID().toString();
            AppointmentInputValidator.BookingInput input =
                    validator.booking(pid, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");

            assertThat(input.providerId()).isEqualTo(UUID.fromString(pid));
            assertThat(input.startTime()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
            assertThat(input.endTime()).isEqualTo(Instant.parse("2026-11-01T11:00:00Z"));
        }

        @Test
        void startTime_noFractionalSeconds_accepted() {
            String pid = UUID.randomUUID().toString();
            AppointmentInputValidator.BookingInput input =
                    validator.booking(pid, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");
            assertThat(input.startTime()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
        }

        @ParameterizedTest(name = "fractional digits = {0}")
        @MethodSource("com.example.scheduler.service.AppointmentInputValidatorTest#validFractionalTimestamps")
        void startTime_validFractionalDigits_accepted(String label, String timestamp) {
            String pid = UUID.randomUUID().toString();
            AppointmentInputValidator.BookingInput input =
                    validator.booking(pid, timestamp, "2026-11-01T23:59:59Z");
            assertThat(input.startTime()).isNotNull();
        }
    }

    static Stream<Arguments> validFractionalTimestamps() {
        return Stream.of(
                arguments("1 digit",  "2026-11-01T10:00:00.1Z"),
                arguments("2 digits", "2026-11-01T10:00:00.12Z"),
                arguments("3 digits", "2026-11-01T10:00:00.123Z"),
                arguments("4 digits", "2026-11-01T10:00:00.1234Z"),
                arguments("5 digits", "2026-11-01T10:00:00.12345Z"),
                arguments("6 digits", "2026-11-01T10:00:00.123456Z")
        );
    }

    // ── booking() — invalid providerId ─────────────────────────────────────────

    @Nested
    class InvalidProviderId {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.example.scheduler.service.AppointmentInputValidatorTest#invalidProviderIds")
        void throwsInvalidAppointmentException(String label, String badId) {
            assertThatThrownBy(() ->
                    validator.booking(badId, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_REQUEST"))
                    .hasMessageContaining("providerId");
        }
    }

    static Stream<Arguments> invalidProviderIds() {
        return Stream.of(
                arguments("null",                   null),
                arguments("empty string",           ""),
                arguments("blank (spaces)",         "   "),
                arguments("random word",            "not-a-uuid"),
                arguments("integer",                "12345"),
                arguments("UUID without hyphens",   "550e8400e29b41d4a716446655440000"),
                arguments("too short",              "550e8400-e29b-41d4-a716"),
                arguments("trailing garbage",       "550e8400-e29b-41d4-a716-446655440000XYZ")
        );
    }

    // ── booking() — invalid startTime / endTime ────────────────────────────────

    @Nested
    class InvalidTimestamp {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.example.scheduler.service.AppointmentInputValidatorTest#invalidTimestamps")
        void startTime_invalid_throwsInvalidTimestamp(String label, String badTs) {
            String pid = UUID.randomUUID().toString();
            assertThatThrownBy(() ->
                    validator.booking(pid, badTs, "2026-11-01T11:00:00Z"))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_TIMESTAMP"));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.example.scheduler.service.AppointmentInputValidatorTest#invalidTimestamps")
        void endTime_invalid_throwsInvalidTimestamp(String label, String badTs) {
            String pid = UUID.randomUUID().toString();
            assertThatThrownBy(() ->
                    validator.booking(pid, "2026-11-01T10:00:00Z", badTs))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_TIMESTAMP"));
        }
    }

    static Stream<Arguments> invalidTimestamps() {
        return Stream.of(
                arguments("null",                         null),
                arguments("empty string",                 ""),
                arguments("blank (spaces)",               "   "),
                arguments("date only",                    "2026-11-01"),
                arguments("missing Z suffix",             "2026-11-01T10:00:00"),
                arguments("space instead of T",           "2026-11-01 10:00:00Z"),
                arguments("non-UTC offset (+05:30)",      "2026-11-01T10:00:00+05:30"),
                arguments("7 fractional digits",          "2026-11-01T10:00:00.1234567Z"),
                arguments("milliseconds with offset",     "2026-11-01T10:00:00.000+00:00"),
                arguments("ISO with timezone name",       "2026-11-01T10:00:00UTC"),
                arguments("random word",                  "not-a-timestamp"),
                arguments("Unix epoch number",            "1762000000")
        );
    }

    // ── booking() — invalid interval ───────────────────────────────────────────

    @Nested
    class InvalidInterval {

        @Test
        void endBeforeStart_throwsInvalidInterval() {
            String pid = UUID.randomUUID().toString();
            assertThatThrownBy(() ->
                    validator.booking(pid, "2026-11-01T11:00:00Z", "2026-11-01T10:00:00Z"))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_INTERVAL"));
        }

        @Test
        void endEqualsStart_zeroLength_throwsInvalidInterval() {
            String pid = UUID.randomUUID().toString();
            assertThatThrownBy(() ->
                    validator.booking(pid, "2026-11-01T10:00:00Z", "2026-11-01T10:00:00Z"))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_INTERVAL"));
        }

        @Test
        void endOneNanosecondBeforeStart_throwsInvalidInterval() {
            // Guarantee the check is on Instant comparison, not string comparison
            String pid = UUID.randomUUID().toString();
            assertThatThrownBy(() ->
                    validator.booking(pid, "2026-11-01T10:00:01Z", "2026-11-01T10:00:00Z"))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_INTERVAL"));
        }
    }

    // ── cancellationId() ───────────────────────────────────────────────────────

    @Nested
    class CancellationId {

        @Test
        void validLowercaseUuid_returnsParsed() {
            UUID id = UUID.randomUUID();
            assertThat(validator.cancellationId(id.toString())).isEqualTo(id);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.example.scheduler.service.AppointmentInputValidatorTest#invalidCancellationIds")
        void invalid_throwsInvalidAppointmentId(String label, String badId) {
            assertThatThrownBy(() -> validator.cancellationId(badId))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .satisfies(ex -> assertThat(((InvalidAppointmentException) ex).code())
                            .isEqualTo("INVALID_APPOINTMENT_ID"));
        }
    }

    static Stream<Arguments> invalidCancellationIds() {
        return Stream.of(
                arguments("null",               null),
                arguments("empty string",       ""),
                arguments("blank (spaces)",     "   "),
                arguments("random word",        "not-a-uuid"),
                arguments("short hex string",   "abc123"),
                arguments("UUID no hyphens",    "550e8400e29b41d4a716446655440000"),
                arguments("integer",            "42")
        );
    }
}
