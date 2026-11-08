-- WA-3b (docs/100-web-analytics.md §7.5, §7.8; doemefu/homelab-data-service#55): path, funnel, audience,
-- Cloudflare-baseline and submit aggregates written by the webanalytics-rollup collector. Id-free: no uuid column,
-- no visitor or visit id. Key columns use '' for "none"; every count is NOT NULL and >= 0. No retention.
-- Never edit this file after it is merged to main; add a new version instead.

-- Page-to-page steps of visits starting on the day; '(entrance)' and '(exit)' close every visit, consecutive identical
-- paths are collapsed, so the transitions from '(entrance)' and to '(exit)' each sum to the visits.
CREATE TABLE webanalytics.daily_transition (
    day          date        NOT NULL,
    entry_locale text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    from_path    text        NOT NULL,
    to_path      text        NOT NULL,
    transitions  integer     NOT NULL CHECK (transitions >= 0),
    computed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_locale, device_class, from_path, to_path)
);

-- Visits reaching each step of the funnels in order (docs/100 §8.5); counts are monotonic by construction.
CREATE TABLE webanalytics.daily_funnel (
    day          date        NOT NULL,
    funnel_id    text        NOT NULL CHECK (funnel_id IN ('offer', 'contact_form')),
    step         smallint    NOT NULL CHECK (step BETWEEN 1 AND 6),
    entry_locale text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    visits       integer     NOT NULL CHECK (visits >= 0),
    computed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, funnel_id, step, entry_locale, device_class)
);

-- Page-group path per visit, e.g. 'home > offer > contact > (exit)'.
CREATE TABLE webanalytics.daily_group_path (
    day               date        NOT NULL,
    path_key          text        NOT NULL CHECK (char_length(path_key) <= 200),
    visits            integer     NOT NULL CHECK (visits >= 0),
    converting_visits integer     NOT NULL CHECK (converting_visits >= 0),
    computed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, path_key)
);

CREATE TABLE webanalytics.daily_entry_exit (
    day         date        NOT NULL,
    entry_path  text        NOT NULL,
    exit_path   text        NOT NULL,
    visits      integer     NOT NULL CHECK (visits >= 0),
    computed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_path, exit_path)
);

-- Visits with contact intent by what they had seen before the first intent; a visit counts once per value.
CREATE TABLE webanalytics.daily_intent_seen (
    day         date        NOT NULL,
    seen        text        NOT NULL CHECK (seen IN ('about', 'projects', 'prices', 'none')),
    visits      integer     NOT NULL CHECK (visits >= 0),
    computed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, seen)
);

-- Visits and engaged visits per audience attribute of the visit's first pageview. No converting_visits: conversions
-- per attribute kept forever could tie attributes to a named enquirer (docs/100 §7.8, rev 3.5). Country: the day's
-- top 50, rest '(other)'.
CREATE TABLE webanalytics.daily_dimension (
    day         date        NOT NULL,
    dimension   text        NOT NULL CHECK (dimension IN ('country', 'browser_family', 'os_family', 'viewport',
                                                          'visitor_type')),
    value          text        NOT NULL DEFAULT '',
    visits         integer     NOT NULL CHECK (visits >= 0),
    engaged_visits integer     NOT NULL CHECK (engaged_visits >= 0),
    computed_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, dimension, value)
);

-- Cloudflare GET 2xx requests to page paths per Zurich day, locale-free path and page locale, from
-- netmon.inbound_request_groups (bots and prefetches included). No IP-derived column.
CREATE TABLE webanalytics.daily_cf_baseline (
    day         date        NOT NULL,
    path        text        NOT NULL,
    locale      text        NOT NULL CHECK (locale IN ('de', 'en')),
    requests    bigint      NOT NULL CHECK (requests >= 0),
    sampled     boolean     NOT NULL,
    computed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, path, locale)
);

-- Goal events per Zurich day of occurred_at (submission day), excluded and orphan visits included; matches
-- daily_enquiries, which counts all submissions by the same day.
CREATE TABLE webanalytics.daily_submits (
    day               date        PRIMARY KEY,
    consented_submits integer     NOT NULL CHECK (consented_submits >= 0),
    computed_at       timestamptz NOT NULL DEFAULT now()
);
