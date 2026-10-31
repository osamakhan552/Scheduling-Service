# Assumptions and booking invariant

**Invariant:** No two committed rows may both be `ACTIVE` for the same `provider_id` and have overlapping half-open intervals. Overlap means `existing.start_time < requested.end_time AND requested.start_time < existing.end_time`. Back-to-back bookings are valid; different providers never conflict. `CANCELLED` rows remain stored and never block a booking.

- Provider and appointment identifiers are canonical UUIDs; a provider registry and referential checks are outside scope. Booking IDs are server-generated.
- Request timestamps are UTC instants ending in uppercase `Z`, with seconds and no more than six fractional digits. `endTime` must strictly follow `startTime`; no minimum or maximum length or future-only rule is imposed.
- Only `ACTIVE` and `CANCELLED` states exist. Cancellation is terminal and does not delete the row. Booking times cannot be edited.
- The exclusion constraint in PostgreSQL, not the preliminary overlap query, is authoritative. `READ_COMMITTED` and a single service-controlled transaction are used for each operation; `@Version` protects competing updates to an appointment row.
- A missing cancellation target returns 404; an already-cancelled target returns 409. Repeated booking requests have no idempotency-key contract.
- `timestamptz(6)` stores microseconds. The existing timestamp parser/audit precision behavior still requires accepted hardening work; see `LIMITATIONS.md`.
