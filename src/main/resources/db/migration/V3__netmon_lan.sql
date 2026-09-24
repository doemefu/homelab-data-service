-- NM-3 (docs/060 §3.3, §3.4): LAN snapshots from the node-script textfile metrics (via Prometheus).
-- Never edit this file after it is merged to main; add a new version instead.
-- src_ip is text: a LAN or public IP literal, the pod CIDR bucket '10.42.0.0/16', or 'other' (§5.2).

-- homelab_lan_connections, 15-min windows. Write mode: replace per (window_start, node). Retention 30 d.
CREATE TABLE netmon.lan_connection_snapshots (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    window_start     timestamptz NOT NULL,
    window_end       timestamptz NOT NULL,
    node             text        NOT NULL,
    dport            integer     NOT NULL,
    src_ip           text        NOT NULL,
    -- conntrack TCP state, e.g. ESTABLISHED, TIME_WAIT.
    state            text        NOT NULL,
    -- max_over_time over the window.
    peak_connections integer     NOT NULL,
    source           text        NOT NULL,
    ingested_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT lan_connection_snapshots_natural_key UNIQUE (window_start, node, dport, src_ip, state),
    CONSTRAINT lan_connection_snapshots_window CHECK (window_end = window_start + interval '15 minutes'),
    CONSTRAINT lan_connection_snapshots_aligned CHECK (extract(epoch FROM window_start)::bigint % 900 = 0),
    CONSTRAINT lan_connection_snapshots_dport CHECK (dport BETWEEN 1 AND 65535),
    CONSTRAINT lan_connection_snapshots_peak CHECK (peak_connections >= 0)
);
CREATE INDEX lan_connection_snapshots_src_ip_idx ON netmon.lan_connection_snapshots (src_ip, window_start);
CREATE INDEX lan_connection_snapshots_window_start_idx ON netmon.lan_connection_snapshots (window_start);

-- homelab_ufw_blocks_bucket, 15-min buckets. Write mode: replace per (window_start, node). Retention 90 d.
CREATE TABLE netmon.ufw_block_snapshots (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    window_start timestamptz NOT NULL,
    window_end   timestamptz NOT NULL,
    node         text        NOT NULL,
    src_ip       text        NOT NULL,
    -- 0 for protocols without ports (e.g. ICMP) and for the overflow row src_ip='other'.
    dport        integer     NOT NULL,
    proto        text        NOT NULL,
    -- Logged blocks in the bucket; a lower bound because UFW logging is rate-limited (§5.4).
    blocks       integer     NOT NULL,
    source       text        NOT NULL,
    ingested_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ufw_block_snapshots_natural_key UNIQUE (window_start, node, src_ip, dport, proto),
    CONSTRAINT ufw_block_snapshots_window CHECK (window_end = window_start + interval '15 minutes'),
    CONSTRAINT ufw_block_snapshots_aligned CHECK (extract(epoch FROM window_start)::bigint % 900 = 0),
    CONSTRAINT ufw_block_snapshots_dport CHECK (dport BETWEEN 0 AND 65535),
    CONSTRAINT ufw_block_snapshots_proto CHECK (proto IN ('TCP', 'UDP', 'ICMP', 'OTHER')),
    CONSTRAINT ufw_block_snapshots_blocks CHECK (blocks >= 0)
);
CREATE INDEX ufw_block_snapshots_window_start_idx ON netmon.ufw_block_snapshots (window_start);
CREATE INDEX ufw_block_snapshots_src_ip_idx ON netmon.ufw_block_snapshots (src_ip, window_start);

-- homelab_sshd_auth_bucket, 15-min buckets. Write mode: replace per (window_start, node). Retention 90 d.
-- Tunnelled SSH shows the cloudflared pod or node address, not the client (§10).
CREATE TABLE netmon.ssh_auth_snapshots (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    window_start timestamptz NOT NULL,
    window_end   timestamptz NOT NULL,
    node         text        NOT NULL,
    src_ip       text        NOT NULL,
    -- Disjoint outcomes (§5.2); failed is a lower bound.
    outcome      text        NOT NULL,
    attempts     integer     NOT NULL,
    source       text        NOT NULL,
    ingested_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ssh_auth_snapshots_natural_key UNIQUE (window_start, node, src_ip, outcome),
    CONSTRAINT ssh_auth_snapshots_window CHECK (window_end = window_start + interval '15 minutes'),
    CONSTRAINT ssh_auth_snapshots_aligned CHECK (extract(epoch FROM window_start)::bigint % 900 = 0),
    CONSTRAINT ssh_auth_snapshots_outcome CHECK (outcome IN ('accepted', 'failed', 'invalid_user')),
    CONSTRAINT ssh_auth_snapshots_attempts CHECK (attempts >= 0)
);
CREATE INDEX ssh_auth_snapshots_window_start_idx ON netmon.ssh_auth_snapshots (window_start);
CREATE INDEX ssh_auth_snapshots_src_ip_idx ON netmon.ssh_auth_snapshots (src_ip, window_start);
