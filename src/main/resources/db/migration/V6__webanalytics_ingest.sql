-- WA-1 (docs/100-web-analytics.md §7.1, §7.2; doemefu/homelab-data-service#53): the webanalytics schema with the
-- raw event table and the two ingest-maintained, id-free daily tables. Aggregates follow in later versions.
-- Never edit this file after it is merged to main; add a new version instead.
-- The ingest validator is a strict superset of these CHECKs: a violation would fail the whole batch and
-- PostgreSQL would log the failing row (incl. visitor_id) in its server log.

CREATE SCHEMA IF NOT EXISTS webanalytics;

-- One row per accepted analytics event (pageview, engagement, click, goal); enquiries never land here.
-- Write mode: INSERT ... ON CONFLICT DO NOTHING (event_id and (pageview_id, seq)). Retention arrives in WA-5.
CREATE TABLE webanalytics.events (
    event_id        uuid        PRIMARY KEY,
    event_type      text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL,
    -- Pseudonymous browser id; never leaves data-service.
    visitor_id      uuid        NOT NULL,
    pageview_id     uuid,
    seq             smallint,
    -- Locale-free canonical path.
    path            text        NOT NULL,
    locale          text        NOT NULL,
    -- Pageview context (pageview rows only).
    landing         boolean,
    referrer_domain text,
    utm_source      text,
    utm_medium      text,
    utm_campaign    text,
    country         text,
    device_class    text,
    browser_family  text,
    os_family       text,
    viewport        text,
    -- Engagement snapshot (engagement rows only).
    engaged_ms      integer,
    scroll_max      smallint,
    fits            boolean,
    section_mask    smallint,
    form_started    boolean,
    -- Click (click rows only); click_target is NULL exactly for mailto/tel.
    click_kind      text,
    click_target    text,
    click_area      text,
    -- Goal (goal rows only).
    goal            text,
    source          text        NOT NULL,
    ingested_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT events_pageview_seq_key UNIQUE (pageview_id, seq),
    CONSTRAINT events_event_type CHECK (event_type IN ('pageview', 'engagement', 'click', 'goal')),
    CONSTRAINT events_time_window
        CHECK (occurred_at <= received_at AND occurred_at >= received_at - interval '15 minutes'),
    CONSTRAINT events_path
        CHECK (char_length(path) <= 200
            AND path ~ '^/([a-z0-9][a-z0-9-]{0,63}(/[a-z0-9][a-z0-9-]{0,63}){0,2})?$'),
    CONSTRAINT events_locale CHECK (locale IN ('de', 'en')),
    CONSTRAINT events_seq CHECK (seq BETWEEN 0 AND 99),
    CONSTRAINT events_country CHECK (country ~ '^[A-Z]{2}$' AND country NOT IN ('XX', 'T1')),
    CONSTRAINT events_device_class CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    CONSTRAINT events_browser_family CHECK (browser_family IN ('edge', 'firefox', 'chrome', 'safari', 'other')),
    CONSTRAINT events_os_family CHECK (os_family IN ('ios', 'android', 'windows', 'macos', 'linux', 'other')),
    CONSTRAINT events_viewport CHECK (viewport IN ('<480', '480-767', '768-1023', '1024-1439', '>=1440')),
    CONSTRAINT events_engaged_ms CHECK (engaged_ms BETWEEN 0 AND 1800000),
    CONSTRAINT events_scroll_max CHECK (scroll_max IN (0, 25, 50, 75, 90, 100)),
    CONSTRAINT events_section_mask CHECK (section_mask BETWEEN 0 AND 255),
    CONSTRAINT events_click_kind
        CHECK (click_kind IN ('cta', 'link', 'faq', 'lang', 'nav', 'outbound', 'mailto', 'tel')),
    CONSTRAINT events_click_area CHECK (click_area IN ('header', 'footer', 'main')),
    CONSTRAINT events_goal CHECK (goal IN ('contact_submit')),
    CONSTRAINT events_pageview_shape CHECK (event_type <> 'pageview' OR (
        pageview_id IS NOT NULL AND seq = 0 AND landing IS NOT NULL
        AND device_class IS NOT NULL AND browser_family IS NOT NULL AND os_family IS NOT NULL
        AND (landing OR (referrer_domain IS NULL AND utm_source IS NULL AND utm_medium IS NULL
                         AND utm_campaign IS NULL))
        AND engaged_ms IS NULL AND scroll_max IS NULL AND fits IS NULL AND section_mask IS NULL
        AND form_started IS NULL
        AND click_kind IS NULL AND click_target IS NULL AND click_area IS NULL
        AND goal IS NULL)),
    CONSTRAINT events_engagement_shape CHECK (event_type <> 'engagement' OR (
        pageview_id IS NOT NULL AND seq BETWEEN 1 AND 99
        AND engaged_ms IS NOT NULL AND scroll_max IS NOT NULL AND fits IS NOT NULL
        AND section_mask IS NOT NULL AND form_started IS NOT NULL
        AND landing IS NULL AND referrer_domain IS NULL AND utm_source IS NULL AND utm_medium IS NULL
        AND utm_campaign IS NULL AND country IS NULL AND device_class IS NULL AND browser_family IS NULL
        AND os_family IS NULL AND viewport IS NULL
        AND click_kind IS NULL AND click_target IS NULL AND click_area IS NULL
        AND goal IS NULL)),
    CONSTRAINT events_click_shape CHECK (event_type <> 'click' OR (
        pageview_id IS NOT NULL AND seq BETWEEN 1 AND 99
        AND click_kind IS NOT NULL AND click_area IS NOT NULL
        AND (click_kind IN ('mailto', 'tel')) = (click_target IS NULL)
        AND landing IS NULL AND referrer_domain IS NULL AND utm_source IS NULL AND utm_medium IS NULL
        AND utm_campaign IS NULL AND country IS NULL AND device_class IS NULL AND browser_family IS NULL
        AND os_family IS NULL AND viewport IS NULL
        AND engaged_ms IS NULL AND scroll_max IS NULL AND fits IS NULL AND section_mask IS NULL
        AND form_started IS NULL
        AND goal IS NULL)),
    CONSTRAINT events_goal_shape CHECK (event_type <> 'goal' OR (
        pageview_id IS NULL AND seq IS NULL AND goal IS NOT NULL
        AND landing IS NULL AND referrer_domain IS NULL AND utm_source IS NULL AND utm_medium IS NULL
        AND utm_campaign IS NULL AND country IS NULL AND device_class IS NULL AND browser_family IS NULL
        AND os_family IS NULL AND viewport IS NULL
        AND engaged_ms IS NULL AND scroll_max IS NULL AND fits IS NULL AND section_mask IS NULL
        AND form_started IS NULL
        AND click_kind IS NULL AND click_target IS NULL AND click_area IS NULL))
);
CREATE INDEX events_occurred_at_idx ON webanalytics.events (occurred_at);
CREATE INDEX events_visitor_id_occurred_at_idx ON webanalytics.events (visitor_id, occurred_at);
CREATE INDEX events_received_at_idx ON webanalytics.events (received_at);
ALTER TABLE webanalytics.events SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_insert_scale_factor = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);

-- Id-free ingest counters per Europe/Zurich day of receipt by data-service. Stages: accepted and duplicate
-- (reason ''), rejected (the reject reason), edge (the furchert-ch edgeDrops key). Upsert-increment at ingest;
-- never touched by the rollup, no retention. Format-only CHECKs so later stages need no migration.
CREATE TABLE webanalytics.daily_ingest_stats (
    day    date   NOT NULL,
    stage  text   NOT NULL,
    reason text   NOT NULL DEFAULT '',
    events bigint NOT NULL,
    CONSTRAINT daily_ingest_stats_pkey PRIMARY KEY (day, stage, reason),
    CONSTRAINT daily_ingest_stats_stage CHECK (stage ~ '^[a-z_]{1,32}$'),
    CONSTRAINT daily_ingest_stats_reason CHECK (reason ~ '^[a-z_]{0,32}$'),
    CONSTRAINT daily_ingest_stats_events CHECK (events >= 0)
);

-- Consent-free count of successful contact submissions per Europe/Zurich day of occurred_at; no identifier.
-- Increment-only, never touched by the rollup, no retention.
CREATE TABLE webanalytics.daily_enquiries (
    day         date   PRIMARY KEY,
    submissions bigint NOT NULL,
    CONSTRAINT daily_enquiries_submissions CHECK (submissions >= 0)
);
