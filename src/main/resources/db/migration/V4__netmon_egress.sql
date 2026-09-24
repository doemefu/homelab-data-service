-- NM-2 (docs/060 §3.3, §3.4): hourly egress flow snapshots from the coroot-node-agent metrics (via Prometheus).
-- Never edit this file after it is merged to main; add a new version instead.
-- Write mode: replace per window_start. Retention 30 d.
CREATE TABLE netmon.egress_flow_snapshots (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    window_start       timestamptz NOT NULL,
    window_end         timestamptz NOT NULL,
    node               text        NOT NULL,
    -- Raw coroot label: /k8s/<ns>/<pod>/<container>, /k8s-cronjob/<ns>/<cronjob>/<container> or a systemd cgroup path.
    container_id       text        NOT NULL,
    -- Parsed from container_id; null for host processes (container is then the unit name, e.g. k3s.service).
    namespace          text,
    pod                text,
    container          text,
    -- The pod name without its ReplicaSet/DaemonSet suffix, or the CronJob name; null for host processes.
    workload           text,
    -- Rollout-stable identity used by is_new: <ns>/<workload>/<container> for pods (the §6.4 <W> format),
    -- the raw container_id otherwise.
    workload_key       text        NOT NULL,
    -- Pre-NAT ip:port, or <fqdn>:<port> for an external destination coroot groups by name.
    destination        text        NOT NULL,
    -- Post-NAT ip:port; '' when coroot reports none (name-grouped destinations, unmatched failed connects).
    actual_destination text        NOT NULL,
    -- From actual_destination, else from destination; null only for name-grouped destinations.
    destination_ip     inet,
    destination_port   integer     NOT NULL,
    destination_scope  text        NOT NULL,
    -- From ip_to_fqdn (lexicographically first name), or the name of a name-grouped destination.
    fqdn               text,
    -- The IP literal, or the name when there is no IP: the destination identity for is_new and the read API.
    destination_host   text        GENERATED ALWAYS AS (coalesce(host(destination_ip), fqdn)) STORED,
    -- increase() over the window, rounded; a lower bound in a series' first window.
    bytes_sent         bigint      NOT NULL,
    bytes_received     bigint      NOT NULL,
    connects           bigint      NOT NULL,
    failed_connects    bigint      NOT NULL,
    -- (workload_key, destination_host, destination_port) not seen in the 30 d before window_start.
    is_new             boolean     NOT NULL,
    source             text        NOT NULL,
    ingested_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT egress_flow_snapshots_natural_key
        UNIQUE (window_start, node, container_id, destination, actual_destination),
    CONSTRAINT egress_flow_snapshots_window CHECK (window_end = window_start + interval '1 hour'),
    CONSTRAINT egress_flow_snapshots_aligned CHECK (extract(epoch FROM window_start)::bigint % 3600 = 0),
    CONSTRAINT egress_flow_snapshots_port CHECK (destination_port BETWEEN 1 AND 65535),
    CONSTRAINT egress_flow_snapshots_scope
        CHECK (destination_scope IN ('pod', 'service', 'lan', 'loopback', 'external')),
    CONSTRAINT egress_flow_snapshots_host CHECK (destination_ip IS NOT NULL OR fqdn IS NOT NULL),
    CONSTRAINT egress_flow_snapshots_counters
        CHECK (bytes_sent >= 0 AND bytes_received >= 0 AND connects >= 0 AND failed_connects >= 0)
);
CREATE INDEX egress_flow_snapshots_window_start_idx ON netmon.egress_flow_snapshots (window_start);
CREATE INDEX egress_flow_snapshots_workload_idx ON netmon.egress_flow_snapshots (workload, window_start);
CREATE INDEX egress_flow_snapshots_destination_ip_idx ON netmon.egress_flow_snapshots (destination_ip);
CREATE INDEX egress_flow_snapshots_identity_idx
    ON netmon.egress_flow_snapshots (workload_key, destination_host, destination_port, window_start);
