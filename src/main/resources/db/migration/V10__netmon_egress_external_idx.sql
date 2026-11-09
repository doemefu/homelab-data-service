-- data-service#57 follow-up (2026-11-09): /egress/top with scope=external (every dashboard call) reads only the
-- ~1.7 % external rows through this partial index instead of every row of the window through (window_start).
-- The query emits the literal predicate destination_scope = 'external' so every plan can use it.
-- Never edit this file after it is merged to main.
CREATE INDEX egress_flow_snapshots_external_idx
    ON netmon.egress_flow_snapshots (window_start)
    WHERE destination_scope = 'external';
