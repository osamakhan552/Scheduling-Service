package com.example.scheduler.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests that run against the local PostgreSQL database
 * configured in src/main/resources/application.yml.
 *
 * Requires a running PostgreSQL instance with the 'scheduler' database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String PROVIDER_A = "550e8400-e29b-41d4-a716-446655440001";
    private static final String PROVIDER_B = "550e8400-e29b-41d4-a716-446655440002";

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM appointments");
    }

    // ── POST /api/v1/appointments ──────────────────────────────────────────────

    @Test
    void book_validRequest_returns201WithBody() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/appointments/")))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.providerId").value(PROVIDER_A))
                .andExpect(jsonPath("$.startTime").value("2026-11-01T10:00:00Z"))
                .andExpect(jsonPath("$.endTime").value("2026-11-01T11:00:00Z"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    @Test
    void book_overlappingSlot_sameProvider_returns409() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z")))
                .andExpect(status().isCreated());

        // Overlap: starts inside the first slot
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T11:00:00Z", "2026-11-01T13:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_CONFLICT"));
    }

    @Test
    void book_overlappingSlot_differentProvider_returns201() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z")))
                .andExpect(status().isCreated());

        // Same time window, different provider — should not conflict
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_B, "2026-11-01T10:00:00Z", "2026-11-01T12:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void book_adjacentSlots_sameProvider_returns201() throws Exception {
        // First slot ends at 11:00
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z")))
                .andExpect(status().isCreated());

        // Next slot starts exactly at 11:00 — half-open [) range means no overlap
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T11:00:00Z", "2026-11-01T12:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void book_missingField_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + PROVIDER_A + "\",\"startTime\":\"2026-11-01T10:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void book_invalidProviderId_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson("not-a-uuid", "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void book_endBeforeStart_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T11:00:00Z", "2026-11-01T10:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTERVAL"));
    }

    @Test
    void book_invalidTimestampFormat_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01 10:00:00", "2026-11-01T11:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIMESTAMP"));
    }

    @Test
    void book_emptyBody_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/appointments/{id}/cancel ─────────────────────────────────

    @Test
    void cancel_activeAppointment_returns200WithCancelledStatus() throws Exception {
        String id = bookAppointment(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");

        mvc.perform(post("/api/v1/appointments/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());
    }

    @Test
    void cancel_afterCancel_canRebookSameSlot() throws Exception {
        String id = bookAppointment(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");
        mvc.perform(post("/api/v1/appointments/{id}/cancel", id)).andExpect(status().isOk());

        // Slot is free again — rebooking should succeed
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void cancel_alreadyCancelled_returns409() throws Exception {
        String id = bookAppointment(PROVIDER_A, "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");
        mvc.perform(post("/api/v1/appointments/{id}/cancel", id)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/appointments/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
    }

    @Test
    void cancel_unknownId_returns404() throws Exception {
        mvc.perform(post("/api/v1/appointments/{id}/cancel",
                        "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }

    @Test
    void cancel_invalidId_returns400() throws Exception {
        mvc.perform(post("/api/v1/appointments/{id}/cancel", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_ID"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String bookAppointment(String providerId, String start, String end) throws Exception {
        return mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(providerId, start, end)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private static String bookingJson(String providerId, String start, String end) {
        return """
                {"providerId":"%s","startTime":"%s","endTime":"%s"}
                """.formatted(providerId, start, end);
    }
}
