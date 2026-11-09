-- data-service#57 (docs/060 §3.3 as amended 2026-11-09): daily aggregate of egress_flow_snapshots.
-- DDL only: data-service fills and repairs the rows per day (recompute with every window replace, plus a per-day
-- repair at the start of each collector run). Never edit this file after it is merged to main.
-- day is the UTC day of the hourly windows: (window_start AT TIME ZONE 'UTC')::date. Retention 32 d.
CREATE TABLE netmon.egress_flow_daily (
    day               date        NOT NULL,
    node              text        NOT NULL,
    workload_key      text        NOT NULL,
    -- As in egress_flow_snapshots; they depend only on workload_key.
    namespace         text,
    workload          text,
    container         text,
    destination_host  text        NOT NULL,
    destination_port  integer     NOT NULL,
    destination_scope text        NOT NULL,
    -- The newest non-null fqdn of the day and the window_start it came from.
    fqdn              text,
    fqdn_window       timestamptz,
    bytes_sent        bigint      NOT NULL,
    bytes_received    bigint      NOT NULL,
    connects          bigint      NOT NULL,
    failed_connects   bigint      NOT NULL,
    -- Earliest window_start of the day.
    first_window      timestamptz NOT NULL,
    -- True if any of the day's hourly rows is new.
    is_new            boolean     NOT NULL,
    -- Number of hourly rows aggregated; weights the node choice of the read API.
    hourly_rows       integer     NOT NULL,
    computed_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT egress_flow_daily_pkey
        PRIMARY KEY (day, workload_key, destination_host, destination_port, destination_scope, node),
    CONSTRAINT egress_flow_daily_port CHECK (destination_port BETWEEN 1 AND 65535),
    CONSTRAINT egress_flow_daily_scope
        CHECK (destination_scope IN ('pod', 'service', 'lan', 'loopback', 'external')),
    CONSTRAINT egress_flow_daily_counters
        CHECK (bytes_sent >= 0 AND bytes_received >= 0 AND connects >= 0 AND failed_connects >= 0),
    CONSTRAINT egress_flow_daily_rows CHECK (hourly_rows >= 1),
    CONSTRAINT egress_flow_daily_fqdn CHECK ((fqdn IS NULL) = (fqdn_window IS NULL))
);
-- is_new look-back by identity and day.
CREATE INDEX egress_flow_daily_identity_idx
    ON netmon.egress_flow_daily (workload_key, destination_host, destination_port, day);
