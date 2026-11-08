package ch.furchert.homelab.data.support;

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
        jdbc.sql("""
                        INSERT INTO webanalytics.rollup_day (day, raw_events, raw_pageviews, visits, orphan_visits,
                            definition_version, final, rolled_up_at)
                        VALUES (:day, 0, 0, 0, 0, 1, :final, :at)
                        """)
                .param("day", day).param("final", fin)
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

    public void enquiries(LocalDate day, int submissions) {
        jdbc.sql("INSERT INTO webanalytics.daily_enquiries (day, submissions) VALUES (:day, :n)")
                .param("day", day).param("n", submissions).update();
    }
}
