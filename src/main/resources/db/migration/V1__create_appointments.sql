CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE appointments (
    id           UUID PRIMARY KEY,
    provider_id  UUID NOT NULL,
    start_time   TIMESTAMPTZ(6) NOT NULL,
    end_time     TIMESTAMPTZ(6) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMPTZ(6),

    CONSTRAINT appointments_positive_duration
        CHECK (end_time > start_time),
    CONSTRAINT appointments_status_valid
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT appointments_version_nonnegative
        CHECK (version >= 0),
    CONSTRAINT appointments_cancellation_consistent
        CHECK (
            (status = 'ACTIVE' AND cancelled_at IS NULL)
            OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        )
);

ALTER TABLE appointments
    ADD CONSTRAINT appointments_no_active_overlap
    EXCLUDE USING GIST (
        provider_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    )
    WHERE (status = 'ACTIVE');

CREATE INDEX appointments_active_provider_start_idx
    ON appointments (provider_id, start_time)
    WHERE status = 'ACTIVE';
