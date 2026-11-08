package ch.furchert.homelab.data.webanalytics.ingest;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/**
 * {@code webanalytics.events}, {@code daily_ingest_stats} and {@code daily_enquiries} (docs/100 §7.2). Runs inside
 * the caller's transaction ({@link IngestService}).
 */
@Repository
public class WebAnalyticsRepository {

    /** Untargeted on purpose: absorbs a concurrent insert on either {@code event_id} or {@code (pageview_id, seq)}. */
    private static final String INSERT_EVENT = """
            INSERT INTO webanalytics.events
                (event_id, event_type, occurred_at, received_at, visitor_id, pageview_id, seq, path, locale,
                 landing, referrer_domain, utm_source, utm_medium, utm_campaign, country, device_class,
                 browser_family, os_family, viewport, engaged_ms, scroll_max, fits, section_mask, form_started,
                 click_kind, click_target, click_area, goal, source)
            VALUES (:eventId, :type, :occurredAt, :receivedAt, :visitorId, :pageviewId, :seq, :path, :locale,
                    :landing, :referrerDomain, :utmSource, :utmMedium, :utmCampaign, :country, :deviceClass,
                    :browserFamily, :osFamily, :viewport, :engagedMs, :scrollMax, :fits, :sectionMask, :formStarted,
                    :clickKind, :clickTarget, :clickArea, :goal, :source)
            ON CONFLICT DO NOTHING
            """;

    private static final String INCREMENT_STATS = """
            INSERT INTO webanalytics.daily_ingest_stats (day, stage, reason, events)
            VALUES (:day, :stage, :reason, :events)
            ON CONFLICT (day, stage, reason) DO UPDATE SET events = webanalytics.daily_ingest_stats.events + EXCLUDED.events
            """;

    private static final String INCREMENT_ENQUIRIES = """
            INSERT INTO webanalytics.daily_enquiries (day, submissions)
            VALUES (:day, :submissions)
            ON CONFLICT (day) DO UPDATE SET submissions = webanalytics.daily_enquiries.submissions + EXCLUDED.submissions
            """;

    /** An existing row's keys. */
    public record StoredKey(UUID eventId, UUID pageviewId, Integer seq) {
    }

    /** One {@code daily_ingest_stats} key; ordered so concurrent batches lock rows in the same order. */
    public record StatsKey(String stage, String reason) implements Comparable<StatsKey> {

        @Override
        public int compareTo(StatsKey other) {
            int byStage = stage.compareTo(other.stage);
            return byStage != 0 ? byStage : reason.compareTo(other.reason);
        }
    }

    private final NamedParameterJdbcTemplate named;

    public WebAnalyticsRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** Applies the batch's 2 s statement timeout (docs/100 §6.2) to the current transaction. */
    public void applyStatementTimeout() {
        named.getJdbcTemplate().execute("SET LOCAL statement_timeout = '2s'");
    }

    /** Rows that already hold one of the event ids or one of the {@code (pageview_id, seq)} pairs. */
    public List<StoredKey> existing(Collection<UUID> eventIds, Collection<Object[]> pageviewSeqPairs) {
        List<String> clauses = new ArrayList<>(2);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!eventIds.isEmpty()) {
            clauses.add("event_id IN (:ids)");
            params.addValue("ids", eventIds);
        }
        if (!pageviewSeqPairs.isEmpty()) {
            clauses.add("(pageview_id, seq) IN (:pairs)");
            params.addValue("pairs", pageviewSeqPairs);
        }
        if (clauses.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT event_id, pageview_id, seq FROM webanalytics.events WHERE " + String.join(" OR ", clauses);
        return named.query(sql, params, (rs, i) -> new StoredKey(
                rs.getObject("event_id", UUID.class),
                rs.getObject("pageview_id", UUID.class),
                rs.getObject("seq") == null ? null : rs.getInt("seq")));
    }

    /** Inserts the events in the given order; element i of the result is 1 if row i was stored, 0 on conflict. */
    public int[] insertEvents(List<ValidEvent> events, String source) {
        if (events.isEmpty()) {
            return new int[0];
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("eventId", e.eventId())
                        .addValue("type", e.type())
                        .addValue("occurredAt", utc(e.occurredAt()))
                        .addValue("receivedAt", utc(e.receivedAt()))
                        .addValue("visitorId", e.visitorId())
                        .addValue("pageviewId", e.pageviewId())
                        .addValue("seq", e.seq())
                        .addValue("path", e.path())
                        .addValue("locale", e.locale())
                        .addValue("landing", e.landing())
                        .addValue("referrerDomain", e.referrerDomain())
                        .addValue("utmSource", e.utmSource())
                        .addValue("utmMedium", e.utmMedium())
                        .addValue("utmCampaign", e.utmCampaign())
                        .addValue("country", e.country())
                        .addValue("deviceClass", e.deviceClass())
                        .addValue("browserFamily", e.browserFamily())
                        .addValue("osFamily", e.osFamily())
                        .addValue("viewport", e.viewport())
                        .addValue("engagedMs", e.engagedMs())
                        .addValue("scrollMax", e.scrollMax())
                        .addValue("fits", e.fits())
                        .addValue("sectionMask", e.sectionMask())
                        .addValue("formStarted", e.formStarted())
                        .addValue("clickKind", e.clickKind())
                        .addValue("clickTarget", e.clickTarget())
                        .addValue("clickArea", e.clickArea())
                        .addValue("goal", e.goal())
                        .addValue("source", source))
                .toArray(SqlParameterSource[]::new);
        return named.batchUpdate(INSERT_EVENT, batch);
    }

    /** Adds the counts to {@code daily_ingest_stats(day, …)}; zero counts are skipped; keys in sorted order. */
    public void incrementIngestStats(LocalDate day, SortedMap<StatsKey, Long> counts) {
        SqlParameterSource[] batch = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new MapSqlParameterSource()
                        .addValue("day", day)
                        .addValue("stage", entry.getKey().stage())
                        .addValue("reason", entry.getKey().reason())
                        .addValue("events", entry.getValue()))
                .toArray(SqlParameterSource[]::new);
        if (batch.length > 0) {
            named.batchUpdate(INCREMENT_STATS, batch);
        }
    }

    /** Adds the counts to {@code daily_enquiries}; keys in sorted order. */
    public void incrementEnquiries(SortedMap<LocalDate, Long> counts) {
        SqlParameterSource[] batch = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new MapSqlParameterSource()
                        .addValue("day", entry.getKey())
                        .addValue("submissions", entry.getValue()))
                .toArray(SqlParameterSource[]::new);
        if (batch.length > 0) {
            named.batchUpdate(INCREMENT_ENQUIRIES, batch);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
