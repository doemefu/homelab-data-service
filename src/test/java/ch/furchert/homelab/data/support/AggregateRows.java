package ch.furchert.homelab.data.support;

import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Seeds WA-3a aggregate rows directly, for read-API tests that must not depend on the rollup. */
public final class AggregateRows {

    private final JdbcClient jdbc;

    public AggregateRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void rollupDay(LocalDate day, boolean fin) {
        rollupDay(day, fin, RollupPlanner.DEFINITION_VERSION);
    }

    public void rollupDay(LocalDate day, boolean fin, int definitionVersion) {
        jdbc.sql("""
                        INSERT INTO webanalytics.rollup_day (day, raw_events, raw_pageviews, visits, orphan_visits,
                            definition_version, final, rolled_up_at)
                        VALUES (:day, 0, 0, 0, 0, :version, :final, :at)
                        """)
                .param("day", day).param("final", fin).param("version", definitionVersion)
                .param("at", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)).update();
    }

    /** {@code daily_visits} with {@code visits} visits, half of them new, offer/engaged/converting as given. */
    public void visits(LocalDate day, String locale, String device, int visits, int offer, int engaged,
                       int converting, int pageviews) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_visits (day, entry_locale, device_class, visits, new_visits,
                            returning_visits, engaged_visits, offer_visits, engaged_offer_visits, contact_intent_visits,
                            form_start_visits, converting_visits, conversions, pageviews, excluded_visits)
                        VALUES (:day, :locale, :device, :visits, :newVisits, :returning, :engaged, :offer, :engaged,
                            0, 0, :converting, :converting, :pageviews, 0)
                        """)
                .param("day", day).param("locale", locale).param("device", device).param("visits", visits)
                .param("newVisits", visits / 2).param("returning", visits - visits / 2).param("engaged", engaged)
                .param("offer", offer).param("converting", converting).param("pageviews", pageviews).update();
    }

    public void visitors(LocalDate day, int visitors, int v7, int v28) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_visitors (day, visitors, new_visitors, returning_visitors,
                            visitors_7d, visitors_28d, repeat_offer_viewers_28d)
                        VALUES (:day, :visitors, 0, :visitors, :v7, :v28, 0)
                        """)
                .param("day", day).param("visitors", visitors).param("v7", v7).param("v28", v28).update();
    }

    public void monthly(LocalDate month, int visitors) {
        jdbc.sql("""
                        INSERT INTO webanalytics.monthly_visitors (month, visitors, new_visitors, returning_visitors,
                            repeat_offer_viewers)
                        VALUES (:month, :visitors, 0, :visitors, 0)
                        """)
                .param("month", month).param("visitors", visitors).update();
    }

    public void page(LocalDate day, String path, String locale, String device, int pageviews, int visitsViewed) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_page (day, path, entry_locale, device_class, pageviews,
                            pageviews_de, pageviews_en, visits_viewed, entries, exits, landing_engaged_visits,
                            landing_converting_visits, engagement_samples, engaged_ms_sum, eng_h0, eng_h1, eng_h2,
                            eng_h3, eng_h4, eng_h5, eng_h6, eng_h7, scroll_samples, scroll_25, scroll_50, scroll_75,
                            scroll_90, scroll_100)
                        VALUES (:day, :path, :locale, :device, :pageviews, :pageviews, 0, :visits, :visits, :visits,
                            0, 0, 1, 15000, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0)
                        """)
                .param("day", day).param("path", path).param("locale", locale).param("device", device)
                .param("pageviews", pageviews).param("visits", visitsViewed).update();
    }

    public void click(LocalDate day, String path, String kind, String target, int clicks, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_click (day, path, entry_locale, device_class, kind, target, area,
                            clicks, visits, converting_visits)
                        VALUES (:day, :path, 'de', 'desktop', :kind, :target, 'main', :clicks, :visits, 0)
                        """)
                .param("day", day).param("path", path).param("kind", kind).param("target", target)
                .param("clicks", clicks).param("visits", visits).update();
    }

    public void section(LocalDate day, String section, int reached, int deepest) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_section (day, entry_locale, device_class, section,
                            reached_pageviews, deepest_at_exit_visits)
                        VALUES (:day, 'de', 'desktop', :section, :reached, :deepest)
                        """)
                .param("day", day).param("section", section).param("reached", reached).param("deepest", deepest)
                .update();
    }

    public void source(LocalDate day, String channel, String referrer, String utmSource, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_source (day, entry_locale, device_class, channel, referrer_domain,
                            utm_source, utm_medium, utm_campaign, visits, new_visits, engaged_visits, offer_visits,
                            converting_visits)
                        VALUES (:day, 'de', 'desktop', :channel, :referrer, :utm, '', '', :visits, 0, 0, 0, 0)
                        """)
                .param("day", day).param("channel", channel).param("referrer", referrer).param("utm", utmSource)
                .param("visits", visits).update();
    }

    public void conversion(LocalDate day, String entryPath, String channel, String firstChannel, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_conversion (day, entry_locale, device_class, entry_path, channel,
                            first_channel, converting_visits, conversions)
                        VALUES (:day, 'de', 'desktop', :entry, :channel, :first, :visits, :visits)
                        """)
                .param("day", day).param("entry", entryPath).param("channel", channel).param("first", firstChannel)
                .param("visits", visits).update();
    }

    public void funnel(LocalDate day, String funnelId, int step, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_funnel (day, funnel_id, step, entry_locale, device_class, visits)
                        VALUES (:day, :funnel, :step, 'de', 'desktop', :visits)
                        """)
                .param("day", day).param("funnel", funnelId).param("step", step).param("visits", visits).update();
    }

    public void transition(LocalDate day, String locale, String from, String to, int transitions) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_transition (day, entry_locale, device_class, from_path, to_path,
                            transitions)
                        VALUES (:day, :locale, 'desktop', :from, :to, :n)
                        """)
                .param("day", day).param("locale", locale).param("from", from).param("to", to)
                .param("n", transitions).update();
    }

    public void groupPath(LocalDate day, String key, int visits, int converting) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_group_path (day, path_key, visits, converting_visits)
                        VALUES (:day, :key, :visits, :converting)
                        """)
                .param("day", day).param("key", key).param("visits", visits).param("converting", converting).update();
    }

    public void entryExit(LocalDate day, String entry, String exit, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_entry_exit (day, entry_path, exit_path, visits)
                        VALUES (:day, :entry, :exit, :visits)
                        """)
                .param("day", day).param("entry", entry).param("exit", exit).param("visits", visits).update();
    }

    public void intentSeen(LocalDate day, String seen, int visits) {
        jdbc.sql("INSERT INTO webanalytics.daily_intent_seen (day, seen, visits) VALUES (:day, :seen, :visits)")
                .param("day", day).param("seen", seen).param("visits", visits).update();
    }

    public void dimension(LocalDate day, String dimension, String value, int visits) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_dimension (day, dimension, value, visits, engaged_visits)
                        VALUES (:day, :dimension, :value, :visits, 0)
                        """)
                .param("day", day).param("dimension", dimension).param("value", value).param("visits", visits).update();
    }

    public void cfBaseline(LocalDate day, String path, String locale, long requests, boolean sampled) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_cf_baseline (day, path, locale, requests, sampled)
                        VALUES (:day, :path, :locale, :requests, :sampled)
                        """)
                .param("day", day).param("path", path).param("locale", locale).param("requests", requests)
                .param("sampled", sampled).update();
    }

    public void submits(LocalDate day, int consented) {
        jdbc.sql("INSERT INTO webanalytics.daily_submits (day, consented_submits) VALUES (:day, :n)")
                .param("day", day).param("n", consented).update();
    }

    public void ingestStats(LocalDate day, String stage, String reason, long events) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_ingest_stats (day, stage, reason, events)
                        VALUES (:day, :stage, :reason, :events)
                        """)
                .param("day", day).param("stage", stage).param("reason", reason).param("events", events).update();
    }

    public void repeatOfferViewers(LocalDate day, int repeat28) {
        jdbc.sql("""
                        INSERT INTO webanalytics.daily_visitors (day, visitors, new_visitors, returning_visitors,
                            visitors_7d, visitors_28d, repeat_offer_viewers_28d)
                        VALUES (:day, 0, 0, 0, 0, 0, :repeat)
                        """)
                .param("day", day).param("repeat", repeat28).update();
    }

    public void enquiries(LocalDate day, int submissions) {
        jdbc.sql("INSERT INTO webanalytics.daily_enquiries (day, submissions) VALUES (:day, :n)")
                .param("day", day).param("n", submissions).update();
    }
}
