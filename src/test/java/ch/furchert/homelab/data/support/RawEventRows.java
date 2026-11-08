package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Inserts {@code webanalytics.events} rows directly (bypassing the ingest time window), so rollup tests can place
 * events on any past day. {@code received_at} equals {@code occurred_at}.
 */
public final class RawEventRows {

    private final JdbcClient jdbc;

    public RawEventRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID pageview(UUID visitor, Instant at, String path) {
        return pageview(visitor, at, path, "de", "desktop", false, null, null);
    }

    public UUID landing(UUID visitor, Instant at, String path, String referrer, String utmSource) {
        return pageview(visitor, at, path, "de", "desktop", true, referrer, utmSource);
    }

    public UUID pageview(UUID visitor, Instant at, String path, String locale, String device, boolean landing,
                         String referrer, String utmSource) {
        UUID pageviewId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            pageview_id, seq, path, locale, landing, referrer_domain, utm_source, device_class,
                            browser_family, os_family, source)
                        VALUES (:id, 'pageview', :at, :at, :visitor, :pv, 0, :path, :locale, :landing, :referrer, :utm,
                            :device, 'firefox', 'macos', 'furchert-ch')
                        """)
                .param("id", UUID.randomUUID()).param("at", utc(at)).param("visitor", visitor).param("pv", pageviewId)
                .param("path", path).param("locale", locale).param("landing", landing)
                .param("referrer", referrer).param("utm", utmSource).param("device", device)
                .update();
        return pageviewId;
    }

    public void engagement(UUID visitor, UUID pageviewId, int seq, Instant at, String path, int engagedMs,
                           int sectionMask) {
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            pageview_id, seq, path, locale, engaged_ms, scroll_max, fits, section_mask, form_started,
                            source)
                        VALUES (:id, 'engagement', :at, :at, :visitor, :pv, :seq, :path, 'de', :engaged, 50, false,
                            :mask, false, 'furchert-ch')
                        """)
                .param("id", UUID.randomUUID()).param("at", utc(at)).param("visitor", visitor).param("pv", pageviewId)
                .param("seq", seq).param("path", path).param("engaged", engagedMs).param("mask", sectionMask)
                .update();
    }

    /** An engagement snapshot with {@code formStarted = true} on {@code /contact}. */
    public void formStart(UUID visitor, UUID pageviewId, int seq, Instant at) {
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            pageview_id, seq, path, locale, engaged_ms, scroll_max, fits, section_mask, form_started,
                            source)
                        VALUES (:id, 'engagement', :at, :at, :visitor, :pv, :seq, '/contact', 'de', 20000, 50, false,
                            0, true, 'furchert-ch')
                        """)
                .param("id", UUID.randomUUID()).param("at", utc(at)).param("visitor", visitor).param("pv", pageviewId)
                .param("seq", seq).update();
    }

    public void click(UUID visitor, UUID pageviewId, int seq, Instant at, String path, String kind, String target) {
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            pageview_id, seq, path, locale, click_kind, click_target, click_area, source)
                        VALUES (:id, 'click', :at, :at, :visitor, :pv, :seq, :path, 'de', :kind, :target, 'main',
                            'furchert-ch')
                        """)
                .param("id", UUID.randomUUID()).param("at", utc(at)).param("visitor", visitor).param("pv", pageviewId)
                .param("seq", seq).param("path", path).param("kind", kind).param("target", target)
                .update();
    }

    public void goal(UUID visitor, Instant at) {
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            path, locale, goal, source)
                        VALUES (:id, 'goal', :at, :at, :visitor, '/contact', 'de', 'contact_submit', 'furchert-ch')
                        """)
                .param("id", UUID.randomUUID()).param("at", utc(at)).param("visitor", visitor)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
