-- NM-0 baseline (docs/060 §3.3, §3.4): the netmon schema and the collector state table only.
-- Telemetry tables arrive in V2 (NM-1), V3 (NM-3), V4 (NM-2) and V5 (NM-4).
-- Never edit this file after it is merged to main; add a new version instead.

CREATE SCHEMA IF NOT EXISTS netmon;

-- One row per collector; drives catch-up and GET /api/netmon/status.
CREATE TABLE netmon.collector_state (
    collector            text        PRIMARY KEY,
    -- High-water mark of fully collected data.
    last_window_end      timestamptz,
    -- Opaque, e.g. the last login-event id.
    cursor               text,
    last_attempt_at      timestamptz,
    last_success_at      timestamptz,
    consecutive_failures integer     NOT NULL DEFAULT 0,
    -- Exception class and a short, collector-authored message. Never a URL with a query string,
    -- a header or a token.
    last_error           text,
    -- Machine-readable error class exposed by the status API as lastErrorCode (docs/060 §7.2).
    last_error_code      text,
    CONSTRAINT collector_state_last_error_code_check
        CHECK (last_error_code IN ('credentials', 'rate_limited', 'upstream', 'truncated', 'internal'))
);
