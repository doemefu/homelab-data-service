-- NM-1 (docs/060 §3.3, §3.4): Cloudflare inbound data, IP enrichment and blocklists.
-- Never edit this file after it is merged to main; add a new version instead.

-- httpRequestsAdaptiveGroups, 1-hour windows. Write mode: replace per window_start. Retention 90 d.
CREATE TABLE netmon.inbound_request_groups (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    window_start    timestamptz NOT NULL,
    window_end      timestamptz NOT NULL,
    -- true once the hour was re-collected >= 15 min after window_end.
    is_final        boolean     NOT NULL,
    client_ip       inet        NOT NULL,
    country         char(2),
    -- No asn/asn_org: httpRequestsAdaptiveGroups has no ASN dimensions (Free-plan probe 2026-09-24);
    -- ASN reaches ip_enrichment from firewall events only.
    host            text        NOT NULL,
    method          text        NOT NULL,
    -- clientRequestPath (no query string), truncated to 1024 chars before aggregation.
    path            text        NOT NULL,
    status          smallint    NOT NULL,
    -- Sample-adjusted count.
    request_count   bigint      NOT NULL,
    -- avg.sampleInterval; 1 means unsampled.
    sample_interval real        NOT NULL,
    sampled         boolean     GENERATED ALWAYS AS (sample_interval > 1) STORED,
    source          text        NOT NULL,
    ingested_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT inbound_request_groups_natural_key UNIQUE (window_start, client_ip, host, method, path, status),
    CONSTRAINT inbound_request_groups_hourly CHECK (window_end = window_start + interval '1 hour'),
    CONSTRAINT inbound_request_groups_path_length CHECK (char_length(path) <= 1024)
);
CREATE INDEX inbound_request_groups_window_start_idx ON netmon.inbound_request_groups (window_start);
CREATE INDEX inbound_request_groups_client_ip_idx ON netmon.inbound_request_groups (client_ip, window_start);
CREATE INDEX inbound_request_groups_host_idx ON netmon.inbound_request_groups (host, window_start);

-- firewallEventsAdaptive, raw events. Write mode: insert, do nothing on conflict. Retention 180 d.
CREATE TABLE netmon.firewall_events (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     timestamptz NOT NULL,
    ray_name        text        NOT NULL,
    client_ip       inet        NOT NULL,
    country         char(2),
    asn             integer,
    asn_org         text,
    action          text        NOT NULL,
    -- Cloudflare's "source" (e.g. firewallManaged); renamed to avoid the common source column.
    security_source text        NOT NULL,
    rule_id         text,
    host            text,
    method          text,
    path            text,
    user_agent      text,
    source          text        NOT NULL,
    ingested_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT firewall_events_path_length CHECK (char_length(path) <= 1024),
    CONSTRAINT firewall_events_user_agent_length CHECK (char_length(user_agent) <= 512)
);
-- One request can trigger several events; the expression key absorbs re-fetched pages.
CREATE UNIQUE INDEX firewall_events_natural_key
    ON netmon.firewall_events (ray_name, security_source, (coalesce(rule_id, '')), action);
CREATE INDEX firewall_events_occurred_at_idx ON netmon.firewall_events (occurred_at);
CREATE INDEX firewall_events_client_ip_idx ON netmon.firewall_events (client_ip, occurred_at);

-- One row per public IP seen by any data set. Write mode: upsert. Retention: last_seen older than 180 d.
CREATE TABLE netmon.ip_enrichment (
    ip                   inet        PRIMARY KEY,
    first_seen           timestamptz NOT NULL,
    last_seen            timestamptz NOT NULL,
    seen_in              text[]      NOT NULL,
    country              char(2),
    asn                  integer,
    asn_org              text,
    -- [{"list":"spamhaus-drop-v4","cidr":"x.x.x.x/nn","fetchedAt":"...Z"}], recomputed on every refresh.
    blocklist_hits       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    -- true iff blocklist_hits is non-empty; maintained together with it.
    blocklisted          boolean     NOT NULL DEFAULT false,
    abuseipdb_score      smallint,
    abuseipdb_reports    integer,
    -- null means never checked.
    abuseipdb_checked_at timestamptz,
    source               text        NOT NULL,
    ingested_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ip_enrichment_seen_in_check
        CHECK (seen_in <@ ARRAY['inbound', 'firewall', 'login', 'lan', 'egress']::text[]),
    CONSTRAINT ip_enrichment_blocklisted_check
        CHECK (blocklisted = (jsonb_array_length(blocklist_hits) > 0)),
    CONSTRAINT ip_enrichment_abuseipdb_score_check
        CHECK (abuseipdb_score BETWEEN 0 AND 100)
);
CREATE INDEX ip_enrichment_last_seen_idx ON netmon.ip_enrichment (last_seen);
CREATE INDEX ip_enrichment_blocklisted_idx ON netmon.ip_enrichment (blocklisted) WHERE blocklisted;
CREATE INDEX ip_enrichment_abuseipdb_checked_at_idx ON netmon.ip_enrichment (abuseipdb_checked_at);

-- One row per blocklist fetch attempt. Write mode: insert. Retention 30 d (FK-safe, docs/060 §3.5).
CREATE TABLE netmon.blocklist_snapshots (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    list_name   text        NOT NULL,
    fetched_at  timestamptz NOT NULL,
    source_url  text        NOT NULL,
    outcome     text        NOT NULL,
    -- Entries after filtering.
    entry_count integer,
    etag        text,
    -- Of the raw body.
    sha256      char(64),
    error       text,
    source      text        NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT blocklist_snapshots_list_name_check CHECK (list_name IN ('spamhaus-drop-v4', 'firehol-level1')),
    CONSTRAINT blocklist_snapshots_outcome_check CHECK (outcome IN ('applied', 'unchanged', 'failed'))
);
CREATE INDEX blocklist_snapshots_list_fetched_idx ON netmon.blocklist_snapshots (list_name, fetched_at);

-- Current entries only. Write mode: replace per list_name in one transaction. Not subject to retention.
CREATE TABLE netmon.blocklist_entries (
    list_name   text        NOT NULL,
    cidr        cidr        NOT NULL,
    snapshot_id bigint      NOT NULL REFERENCES netmon.blocklist_snapshots (id),
    source      text        NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (list_name, cidr)
);
-- ip <<= cidr matching.
CREATE INDEX blocklist_entries_cidr_idx ON netmon.blocklist_entries USING gist (cidr inet_ops);
-- The retention guard's NOT EXISTS lookup.
CREATE INDEX blocklist_entries_snapshot_id_idx ON netmon.blocklist_entries (snapshot_id);
