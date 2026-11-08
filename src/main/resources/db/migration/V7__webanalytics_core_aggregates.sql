-- WA-3a (docs/100-web-analytics.md §7.5, §7.8; doemefu/homelab-data-service#54): the core aggregate tables written
-- by the webanalytics-rollup collector, and rollup_day. Id-free: no uuid column, no visitor or visit id.
-- Key columns use '' for "none"; every count is NOT NULL and >= 0. Replace per day; no retention.
-- Never edit this file after it is merged to main; add a new version instead.

CREATE TABLE webanalytics.rollup_day (
    day                date        PRIMARY KEY,
    raw_events         integer     NOT NULL CHECK (raw_events >= 0),
    raw_pageviews      integer     NOT NULL CHECK (raw_pageviews >= 0),
    visits             integer     NOT NULL CHECK (visits >= 0),
    orphan_visits      integer     NOT NULL CHECK (orphan_visits >= 0),
    definition_version smallint    NOT NULL CHECK (definition_version > 0),
    -- false = provisional (rolled up before the day closed at D + 1 07:15 Zurich); never served.
    final              boolean     NOT NULL,
    rolled_up_at       timestamptz NOT NULL
);

CREATE TABLE webanalytics.daily_visits (
    day                   date        NOT NULL,
    entry_locale          text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class          text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    visits                integer     NOT NULL CHECK (visits >= 0),
    new_visits            integer     NOT NULL CHECK (new_visits >= 0),
    returning_visits      integer     NOT NULL CHECK (returning_visits >= 0),
    engaged_visits        integer     NOT NULL CHECK (engaged_visits >= 0),
    offer_visits          integer     NOT NULL CHECK (offer_visits >= 0),
    engaged_offer_visits  integer     NOT NULL CHECK (engaged_offer_visits >= 0),
    contact_intent_visits integer     NOT NULL CHECK (contact_intent_visits >= 0),
    form_start_visits     integer     NOT NULL CHECK (form_start_visits >= 0),
    converting_visits     integer     NOT NULL CHECK (converting_visits >= 0),
    conversions           integer     NOT NULL CHECK (conversions >= 0),
    pageviews             integer     NOT NULL CHECK (pageviews >= 0),
    excluded_visits       integer     NOT NULL CHECK (excluded_visits >= 0),
    computed_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_locale, device_class)
);

CREATE TABLE webanalytics.daily_visitors (
    day                      date        PRIMARY KEY,
    visitors                 integer     NOT NULL CHECK (visitors >= 0),
    new_visitors             integer     NOT NULL CHECK (new_visitors >= 0),
    returning_visitors       integer     NOT NULL CHECK (returning_visitors >= 0),
    visitors_7d              integer     NOT NULL CHECK (visitors_7d >= 0),
    visitors_28d             integer     NOT NULL CHECK (visitors_28d >= 0),
    repeat_offer_viewers_28d integer     NOT NULL CHECK (repeat_offer_viewers_28d >= 0),
    computed_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE webanalytics.monthly_visitors (
    -- First day of the Zurich month.
    month                date        PRIMARY KEY CHECK (extract(day FROM month) = 1),
    visitors             integer     NOT NULL CHECK (visitors >= 0),
    new_visitors         integer     NOT NULL CHECK (new_visitors >= 0),
    returning_visitors   integer     NOT NULL CHECK (returning_visitors >= 0),
    repeat_offer_viewers integer     NOT NULL CHECK (repeat_offer_viewers >= 0),
    computed_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE webanalytics.daily_page (
    day                       date        NOT NULL,
    path                      text        NOT NULL,
    entry_locale              text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class              text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    pageviews                 integer     NOT NULL CHECK (pageviews >= 0),
    pageviews_de              integer     NOT NULL CHECK (pageviews_de >= 0),
    pageviews_en              integer     NOT NULL CHECK (pageviews_en >= 0),
    visits_viewed             integer     NOT NULL CHECK (visits_viewed >= 0),
    entries                   integer     NOT NULL CHECK (entries >= 0),
    exits                     integer     NOT NULL CHECK (exits >= 0),
    landing_engaged_visits    integer     NOT NULL CHECK (landing_engaged_visits >= 0),
    landing_converting_visits integer     NOT NULL CHECK (landing_converting_visits >= 0),
    engagement_samples        integer     NOT NULL CHECK (engagement_samples >= 0),
    engaged_ms_sum            bigint      NOT NULL CHECK (engaged_ms_sum >= 0),
    -- Engaged-time histogram per pageview: 0-5 s, 5-10 s, 10-30 s, 30-60 s, 1-2 min, 2-5 min, 5-10 min, > 10 min.
    eng_h0                    integer     NOT NULL CHECK (eng_h0 >= 0),
    eng_h1                    integer     NOT NULL CHECK (eng_h1 >= 0),
    eng_h2                    integer     NOT NULL CHECK (eng_h2 >= 0),
    eng_h3                    integer     NOT NULL CHECK (eng_h3 >= 0),
    eng_h4                    integer     NOT NULL CHECK (eng_h4 >= 0),
    eng_h5                    integer     NOT NULL CHECK (eng_h5 >= 0),
    eng_h6                    integer     NOT NULL CHECK (eng_h6 >= 0),
    eng_h7                    integer     NOT NULL CHECK (eng_h7 >= 0),
    scroll_samples            integer     NOT NULL CHECK (scroll_samples >= 0),
    scroll_25                 integer     NOT NULL CHECK (scroll_25 >= 0),
    scroll_50                 integer     NOT NULL CHECK (scroll_50 >= 0),
    scroll_75                 integer     NOT NULL CHECK (scroll_75 >= 0),
    scroll_90                 integer     NOT NULL CHECK (scroll_90 >= 0),
    scroll_100                integer     NOT NULL CHECK (scroll_100 >= 0),
    computed_at               timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, path, entry_locale, device_class)
);

CREATE TABLE webanalytics.daily_section (
    day                    date        NOT NULL,
    entry_locale           text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class           text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    section                text        NOT NULL CHECK (section IN ('hero', 'so-arbeite-ich', 'beispiel', 'preise',
                                                                   'daten', 'ueber-mich', 'faq', 'final')),
    reached_pageviews      integer     NOT NULL CHECK (reached_pageviews >= 0),
    deepest_at_exit_visits integer     NOT NULL CHECK (deepest_at_exit_visits >= 0),
    computed_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_locale, device_class, section)
);

CREATE TABLE webanalytics.daily_click (
    day               date        NOT NULL,
    path              text        NOT NULL,
    entry_locale      text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class      text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    kind              text        NOT NULL CHECK (kind IN ('cta', 'link', 'faq', 'lang', 'nav', 'outbound', 'mailto',
                                                         'tel')),
    -- '' for mailto/tel; '(other)' for outbound targets outside the day's top 50.
    target            text        NOT NULL DEFAULT '',
    area              text        NOT NULL CHECK (area IN ('header', 'footer', 'main')),
    clicks            integer     NOT NULL CHECK (clicks >= 0),
    visits            integer     NOT NULL CHECK (visits >= 0),
    converting_visits integer     NOT NULL CHECK (converting_visits >= 0),
    computed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, path, entry_locale, device_class, kind, target, area)
);

CREATE TABLE webanalytics.daily_source (
    day               date        NOT NULL,
    entry_locale      text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class      text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    channel           text        NOT NULL CHECK (channel IN ('campaign', 'ai', 'search', 'social', 'referral',
                                                              'direct', 'internal')),
    referrer_domain   text        NOT NULL DEFAULT '',
    utm_source        text        NOT NULL DEFAULT '',
    utm_medium        text        NOT NULL DEFAULT '',
    utm_campaign      text        NOT NULL DEFAULT '',
    visits            integer     NOT NULL CHECK (visits >= 0),
    new_visits        integer     NOT NULL CHECK (new_visits >= 0),
    engaged_visits    integer     NOT NULL CHECK (engaged_visits >= 0),
    offer_visits      integer     NOT NULL CHECK (offer_visits >= 0),
    converting_visits integer     NOT NULL CHECK (converting_visits >= 0),
    computed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_locale, device_class, channel, referrer_domain, utm_source, utm_medium, utm_campaign)
);

CREATE TABLE webanalytics.daily_conversion (
    day               date        NOT NULL,
    entry_locale      text        NOT NULL CHECK (entry_locale IN ('de', 'en')),
    device_class      text        NOT NULL CHECK (device_class IN ('mobile', 'tablet', 'desktop', 'other')),
    entry_path        text        NOT NULL,
    channel           text        NOT NULL CHECK (channel IN ('campaign', 'ai', 'search', 'social', 'referral',
                                                              'direct', 'internal')),
    -- Channel of the visitor's earliest known landing; '' when none is known.
    first_channel     text        NOT NULL DEFAULT '' CHECK (first_channel IN ('', 'campaign', 'ai', 'search', 'social',
                                                                               'referral', 'direct', 'internal')),
    converting_visits integer     NOT NULL CHECK (converting_visits >= 0),
    conversions       integer     NOT NULL CHECK (conversions >= 0),
    computed_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (day, entry_locale, device_class, entry_path, channel, first_channel)
);
