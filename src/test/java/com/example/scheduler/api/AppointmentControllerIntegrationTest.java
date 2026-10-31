package com.example.scheduler.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests using a real PostgreSQL via Testcontainers.
 *
 * The container is started once per JVM and shared across all nested test
 * classes.  Each test method begins with an empty {@code appointments} table
 * (truncated in {@link #cleanSlate()}).
 *
 * Every rejection test asserts that the row count did not change so we
 * confirm atomicity: no partial inserts or phantom rows survive.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AppointmentControllerIntegrationTest {

    // One container for the whole test class — Flyway runs V1 migration on first start.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ── Provider UUIDs ─────────────────────────────────────────────────────────
    static final String P1 = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String P2 = "aaaaaaaa-0000-0000-0000-000000000002";

    // ── Anchor timestamps (all on 2026-11-01, UTC) ─────────────────────────────
    static final String T08 = "2026-11-01T08:00:00Z";
    static final String T09 = "2026-11-01T09:00:00Z";
    static final String T10 = "2026-11-01T10:00:00Z";
    static final String T1030 = "2026-11-01T10:30:00Z";
    static final String T11 = "2026-11-01T11:00:00Z";
    static final String T1130 = "2026-11-01T11:30:00Z";
    static final String T12 = "2026-11-01T12:00:00Z";
    static final String T13 = "2026-11-01T13:00:00Z";

    @BeforeEach
    void cleanSlate() {
        jdbc.execute("DELETE FROM appointments");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Returns current row count in the appointments table. */
    long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Long.class);
    }

    /**
     * Books an appointment and returns the assigned id.
     * Asserts HTTP 201 so a failure here points to the pre-condition, not the scenario.
     */
    String book(String provider, String start, String end) throws Exception {
        String body = mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(provider, start, end)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // extract "id" field with a simple regex — avoids a full Jackson dependency
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    /**
     * Builds a booking JSON body.
     * Pass {@code null} for a field to omit it from the JSON entirely
     * (which triggers the {@code @NotBlank} validation).
     */
    static String bookingBody(String providerId, String start, String end) {
        StringBuilder sb = new StringBuilder("{");
        if (providerId != null) sb.append("\"providerId\":\"").append(providerId).append("\",");
        if (start != null)      sb.append("\"startTime\":\"").append(start).append("\",");
        if (end != null)        sb.append("\"endTime\":\"").append(end).append("\",");
        // strip trailing comma then close
        return sb.toString().replaceAll(",\\s*$", "") + "}";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1.  Successful booking
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class SuccessfulBooking {

        @Test
        void returns201_withFullBody_andOneRowInserted() throws Exception {
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T10, T11)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.id").isString())
                    .andExpect(jsonPath("$.providerId").value(P1))
                    .andExpect(jsonPath("$.startTime").value(T10))
                    .andExpect(jsonPath("$.endTime").value(T11))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.cancelledAt").doesNotExist());

            assertThat(rowCount()).isEqualTo(before + 1);
        }

        @Test
        void locationHeader_pointsToNewResource() throws Exception {
            String id = book(P1, T10, T11);

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T12, T13)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location",
                            org.hamcrest.Matchers.containsString("/api/v1/appointments/")));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2.  Same-provider overlaps — all four geometric cases + identical
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class SameProviderOverlap {

        /**
         * Existing: [T10, T12)   New: [T11, T13)  — new STARTS inside existing.
         * Overlap is the interval [T11, T12).
         */
        @Test
        void partial_trailing_returns409_noRowAdded() throws Exception {
            book(P1, T10, T12);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T11, T13)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));

            assertThat(rowCount()).as("no row added on trailing partial overlap").isEqualTo(before);
        }

        /**
         * Existing: [T11, T13)   New: [T10, T12)  — new ENDS inside existing.
         * Overlap is the interval [T11, T12).
         */
        @Test
        void partial_leading_returns409_noRowAdded() throws Exception {
            book(P1, T11, T13);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T10, T12)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));

            assertThat(rowCount()).as("no row added on leading partial overlap").isEqualTo(before);
        }

        /**
         * Existing: [T10, T12)   New: [T1030, T1130)  — new is CONTAINED inside existing.
         */
        @Test
        void contained_newInsideExisting_returns409_noRowAdded() throws Exception {
            book(P1, T10, T12);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T1030, T1130)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));

            assertThat(rowCount()).as("no row added when new is contained in existing").isEqualTo(before);
        }

        /**
         * Existing: [T1030, T1130)   New: [T10, T12)  — new CONTAINS existing.
         */
        @Test
        void containing_newWrapsExisting_returns409_noRowAdded() throws Exception {
            book(P1, T1030, T1130);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T10, T12)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));

            assertThat(rowCount()).as("no row added when new contains existing").isEqualTo(before);
        }

        /**
         * New is exactly the same interval as the existing booking.
         */
        @Test
        void identical_returns409_noRowAdded() throws Exception {
            book(P1, T10, T11);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T10, T11)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));

            assertThat(rowCount()).as("no row added for identical interval").isEqualTo(before);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3.  Adjacency — half-open [start, end) means touching endpoints are OK
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class Adjacency {

        /**
         * Existing: [T10, T12)   New: [T08, T10)
         * New ends exactly when existing starts — no overlap under [).
         */
        @Test
        void newImmediatelyBefore_returns201() throws Exception {
            book(P1, T10, T12);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T08, T10)))
                    .andExpect(status().isCreated());

            assertThat(rowCount()).isEqualTo(before + 1);
        }

        /**
         * Existing: [T10, T12)   New: [T12, T13)
         * New starts exactly when existing ends — no overlap under [).
         */
        @Test
        void newImmediatelyAfter_returns201() throws Exception {
            book(P1, T10, T12);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P1, T12, T13)))
                    .andExpect(status().isCreated());

            assertThat(rowCount()).isEqualTo(before + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4.  Different providers — same slot is never a conflict
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class DifferentProviders {

        @Test
        void identicalSlot_differentProvider_bothSucceed() throws Exception {
            book(P1, T10, T12);
            long before = rowCount(); // 1 row

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P2, T10, T12)))
                    .andExpect(status().isCreated());

            assertThat(rowCount()).isEqualTo(before + 1); // 2 rows total
        }

        @Test
        void overlappingSlot_differentProvider_bothSucceed() throws Exception {
            book(P1, T10, T12);
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookingBody(P2, T11, T13))) // overlaps P1's slot, but P2
                    .andExpect(status().isCreated());

            assertThat(rowCount()).isEqualTo(before + 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5.  Cancel then rebook
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class CancelAndRebook {

        @Test
        void cancelledSlot_canBeRebooked_byAnyProvider() throws Exception {
            // Book then cancel
            String id = book(P1, T10, T11);

            mvc.perform(post("/api/v1/appointments/{id}/cancel", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancelledAt").isString());

            long before = rowCount(); // 1 row (CANCELLED)

            // Same provider, same slot — must succeed now that the old one is cancelled
            String rebookedId = book(P1, T10, T11);
            assertThat(rebookedId).isNotEqualTo(id); // new id
            assertThat(rowCount()).isEqualTo(before + 1); // 2 rows: 1 cancelled + 1 active
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6.  Cancel edge cases
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class CancelEdgeCases {

        @Test
        void cancelMissing_returns404_noRowChanged() throws Exception {
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments/{id}/cancel",
                            "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));

            assertThat(rowCount()).as("no row added on cancel-missing").isEqualTo(before);
        }

        @Test
        void cancelTwice_returns409_noRowAdded() throws Exception {
            String id = book(P1, T10, T11);
            mvc.perform(post("/api/v1/appointments/{id}/cancel", id))
                    .andExpect(status().isOk());

            long before = rowCount(); // 1 row (CANCELLED)

            mvc.perform(post("/api/v1/appointments/{id}/cancel", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));

            assertThat(rowCount()).as("no row added on double cancel").isEqualTo(before);
        }

        @Test
        void cancelWithInvalidUuid_returns400_noRowChanged() throws Exception {
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments/{id}/cancel", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_ID"));

            assertThat(rowCount()).isEqualTo(before);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7.  Table-driven invalid booking input
    //     Every case must return 4xx AND leave the table unchanged.
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    class InvalidBookingInput {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.example.scheduler.api.AppointmentControllerIntegrationTest#invalidBookingCases")
        void returns4xx_andNoRowInserted(String label, String requestBody, int expectedStatus)
                throws Exception {
            long before = rowCount();

            mvc.perform(post("/api/v1/appointments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().is(expectedStatus));

            assertThat(rowCount())
                    .as("no row should be inserted for rejected input: %s", label)
                    .isEqualTo(before);
        }
    }

    static Stream<Arguments> invalidBookingCases() {
        // ── providerId problems ────────────────────────────────────────────────
        return Stream.of(

            // --- providerId ---
            arguments("missing providerId",
                    "{\"startTime\":\"" + T10 + "\",\"endTime\":\"" + T11 + "\"}", 400),
            arguments("blank providerId",
                    bookingBody("   ", T10, T11), 400),
            arguments("providerId: plain word",
                    bookingBody("not-a-uuid", T10, T11), 400),
            arguments("providerId: integer string",
                    bookingBody("12345", T10, T11), 400),
            arguments("providerId: UUID without hyphens",
                    bookingBody("550e8400e29b41d4a716446655440000", T10, T11), 400),
            arguments("providerId: too short",
                    bookingBody("550e8400-e29b-41d4-a716", T10, T11), 400),

            // --- startTime ---
            arguments("missing startTime",
                    "{\"providerId\":\"" + P1 + "\",\"endTime\":\"" + T11 + "\"}", 400),
            arguments("blank startTime",
                    bookingBody(P1, "   ", T11), 400),
            arguments("startTime: date only (no time)",
                    bookingBody(P1, "2026-11-01", T11), 400),
            arguments("startTime: missing Z suffix",
                    bookingBody(P1, "2026-11-01T10:00:00", T11), 400),
            arguments("startTime: space instead of T",
                    bookingBody(P1, "2026-11-01 10:00:00Z", T11), 400),
            arguments("startTime: non-UTC offset (+05:30)",
                    bookingBody(P1, "2026-11-01T10:00:00+05:30", T11), 400),
            arguments("startTime: 7 fractional digits",
                    bookingBody(P1, "2026-11-01T10:00:00.1234567Z", T11), 400),
            arguments("startTime: unix epoch number",
                    bookingBody(P1, "1762000000", T11), 400),

            // --- endTime ---
            arguments("missing endTime",
                    "{\"providerId\":\"" + P1 + "\",\"startTime\":\"" + T10 + "\"}", 400),
            arguments("blank endTime",
                    bookingBody(P1, T10, "   "), 400),
            arguments("endTime: missing Z suffix",
                    bookingBody(P1, T10, "2026-11-01T11:00:00"), 400),
            arguments("endTime: non-UTC offset",
                    bookingBody(P1, T10, "2026-11-01T11:00:00+00:00"), 400),

            // --- interval logic ---
            arguments("end == start (zero-length)",
                    bookingBody(P1, T10, T10), 400),
            arguments("end before start",
                    bookingBody(P1, T11, T10), 400),

            // --- malformed JSON ---
            arguments("empty JSON object {}",
                    "{}", 400),
            arguments("empty body",
                    "", 400),
            arguments("not JSON",
                    "this is not json", 400)
        );
    }
}
