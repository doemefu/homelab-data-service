package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6__webanalytics_ingest (docs/100 §7.1, §7.2). */
class WebAnalyticsMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
    }

    @Test
    void historyRecordsV6() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '6' AND description = 'webanalytics ingest'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }

    @Test
    void schemaHoldsTheThreeWa1Tables() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'webanalytics'")
                .query(String.class).list();
        assertThat(tables).containsExactlyInAnyOrder("events", "daily_ingest_stats", "daily_enquiries");
    }

    @Test
    void eventsHasTheContractColumns() {
        assertThat(columns("events")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("event_id", "uuid:NO"),
                Map.entry("event_type", "text:NO"),
                Map.entry("occurred_at", "timestamp with time zone:NO"),
                Map.entry("received_at", "timestamp with time zone:NO"),
                Map.entry("visitor_id", "uuid:NO"),
                Map.entry("pageview_id", "uuid:YES"),
                Map.entry("seq", "smallint:YES"),
                Map.entry("path", "text:NO"),
                Map.entry("locale", "text:NO"),
                Map.entry("landing", "boolean:YES"),
                Map.entry("referrer_domain", "text:YES"),
                Map.entry("utm_source", "text:YES"),
                Map.entry("utm_medium", "text:YES"),
                Map.entry("utm_campaign", "text:YES"),
                Map.entry("country", "text:YES"),
                Map.entry("device_class", "text:YES"),
                Map.entry("browser_family", "text:YES"),
                Map.entry("os_family", "text:YES"),
                Map.entry("viewport", "text:YES"),
                Map.entry("engaged_ms", "integer:YES"),
                Map.entry("scroll_max", "smallint:YES"),
                Map.entry("fits", "boolean:YES"),
                Map.entry("section_mask", "smallint:YES"),
                Map.entry("form_started", "boolean:YES"),
                Map.entry("click_kind", "text:YES"),
                Map.entry("click_target", "text:YES"),
                Map.entry("click_area", "text:YES"),
                Map.entry("goal", "text:YES"),
                Map.entry("source", "text:NO"),
                Map.entry("ingested_at", "timestamp with time zone:NO")));
    }

    @Test
    void dailyTablesHaveTheContractColumns() {
        assertThat(columns("daily_ingest_stats")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "day", "date:NO", "stage", "text:NO", "reason", "text:NO", "events", "bigint:NO"));
        assertThat(columns("daily_enquiries")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "day", "date:NO", "submissions", "bigint:NO"));
    }

    @Test
    void indexesAndStorageParametersFollowTheSpec() {
        List<String> indexes = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'webanalytics' AND tablename = 'events'")
                .query(String.class).list();
        assertThat(indexes).anyMatch(d -> d.contains("UNIQUE") && d.endsWith("(event_id)"))
                .anyMatch(d -> d.contains("UNIQUE") && d.endsWith("(pageview_id, seq)"))
                .anyMatch(d -> d.endsWith("(occurred_at)"))
                .anyMatch(d -> d.endsWith("(visitor_id, occurred_at)"))
                .anyMatch(d -> d.endsWith("(received_at)"));
        String options = jdbc.sql("""
                        SELECT array_to_string(reloptions, ',') FROM pg_class
                        WHERE oid = 'webanalytics.events'::regclass
                        """)
                .query(String.class).single();
        assertThat(options).contains("autovacuum_vacuum_scale_factor=0.02", "autovacuum_vacuum_insert_scale_factor=0.05",
                "autovacuum_analyze_scale_factor=0.02");
    }

    @Test
    void validRowsOfEveryTypeAreAccepted() {
        insert("pageview", "now() - interval '1 minute'", "now()", "gen_random_uuid()", "0",
                "landing, device_class, browser_family, os_family", "true, 'desktop', 'firefox', 'macos'");
        insert("engagement", "now()", "now()", "gen_random_uuid()", "1",
                "engaged_ms, scroll_max, fits, section_mask, form_started", "1000, 25, false, 0, false");
        insert("click", "now()", "now()", "gen_random_uuid()", "1", "click_kind, click_area", "'mailto', 'main'");
        insert("goal", "now()", "now()", "NULL", "NULL", "goal", "'contact_submit'");
        insert("goal", "now()", "now()", "NULL", "NULL", "goal", "'contact_submit'"); // NULL keys never conflict
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isEqualTo(5L);
    }

    @Test
    void checksRejectRowsOutsideTheContract() {
        // occurred_at more than 15 minutes before received_at
        assertRejected(() -> insert("goal", "now() - interval '16 minutes'", "now()", "NULL", "NULL", "goal", "'contact_submit'"));
        // a pageview carrying a click field
        assertRejected(() -> insert("pageview", "now()", "now()", "gen_random_uuid()", "0",
                "landing, device_class, browser_family, os_family, click_kind",
                "true, 'desktop', 'firefox', 'macos', 'cta'"));
        // mailto with a target
        assertRejected(() -> insert("click", "now()", "now()", "gen_random_uuid()", "1",
                "click_kind, click_target, click_area", "'mailto', 'someone@example.com', 'main'"));
        // enquiries are never stored
        assertRejected(() -> insert("enquiry", "now()", "now()", "NULL", "NULL", "goal", "'contact_submit'"));
        // path with a trailing slash
        assertRejected(() -> jdbc.sql("""
                INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id, path, locale,
                    goal, source)
                VALUES (gen_random_uuid(), 'goal', now(), now(), gen_random_uuid(), '/contact/', 'de', 'contact_submit',
                    'furchert-ch')
                """).update());
        // stage format
        assertRejected(() -> jdbc.sql("INSERT INTO webanalytics.daily_ingest_stats (day, stage, reason, events) VALUES (current_date, 'Edge', '', 1)").update());
        assertRejected(() -> jdbc.sql("INSERT INTO webanalytics.daily_enquiries (day, submissions) VALUES (current_date, -1)").update());
    }

    @Test
    void pageviewSeqIsUnique() {
        jdbc.sql("""
                INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id, pageview_id, seq,
                    path, locale, click_kind, click_target, click_area, source)
                VALUES (gen_random_uuid(), 'click', now(), now(), gen_random_uuid(), '6f1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d', 1,
                    '/automation', 'de', 'cta', 'automation-hero', 'main', 'furchert-ch')
                """).update();
        assertRejected(() -> jdbc.sql("""
                INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id, pageview_id, seq,
                    path, locale, click_kind, click_target, click_area, source)
                VALUES (gen_random_uuid(), 'click', now(), now(), gen_random_uuid(), '6f1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d', 1,
                    '/automation', 'de', 'cta', 'other-id', 'main', 'furchert-ch')
                """).update());
    }

    private Map<String, String> columns(String table) {
        return jdbc.sql("""
                        SELECT column_name, data_type || ':' || is_nullable AS spec
                        FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name = :table
                        """)
                .param("table", table)
                .query((rs, i) -> Map.entry(rs.getString("column_name"), rs.getString("spec")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** SQL fragments are fixed test literals. */
    private void insert(String type, String occurredAt, String receivedAt, String pageviewId, String seq,
                        String extraColumns, String extraValues) {
        jdbc.sql("""
                INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id, pageview_id,
                    seq, path, locale, source, %s)
                VALUES (gen_random_uuid(), '%s', %s, %s, gen_random_uuid(), %s, %s, '/automation', 'de', 'furchert-ch', %s)
                """.formatted(extraColumns, type, occurredAt, receivedAt, pageviewId, seq, extraValues)).update();
    }

    private static void assertRejected(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}
