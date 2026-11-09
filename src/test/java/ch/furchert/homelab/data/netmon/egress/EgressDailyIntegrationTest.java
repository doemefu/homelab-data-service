package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.EgressProperties;
import ch.furchert.homelab.data.config.RetentionProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.lan.FakePrometheus;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static ch.furchert.homelab.data.netmon.lan.FakePrometheus.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The daily aggregate {@code egress_flow_daily} (docs/060 §3.3 after data-service#57): recomputed with every window
 * replace (also an emptied window), idempotent, the {@code is_new} look-back over the same day's hourly rows plus 30
 * daily days, and the per-day repair loop (missing days newest first, stale days, no collection while a look-back day
 * lacks daily rows).
 */
class EgressDailyIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-24T10:07:30Z");
    private static final String LITELLM = "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm";
    private static final String KEY = "apps/litellm/litellm";

    @Autowired
    JdbcClient jdbc;
    @Autowired
    EgressSnapshotRepository repository;
    @Autowired
    IpEnrichmentService enrichment;
    @Autowired
    CollectorStateRepository state;
    @Autowired
    CollectorRunner runner;

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    /** Raw retention 3 days (≠ 8) to show that is_new does not depend on it. */
    private static RetentionProperties retention() {
        return new RetentionProperties(90, 180, 180, 30, 30, 90, 90, 3, 180, 32);
    }

    private EgressCollector collector(FakePrometheus prometheus, int maxRepairDays) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new EgressCollector(prometheus, repository, enrichment, state,
                new EgressProperties("10.42.0.0/16", "10.43.0.0/16", "192.168.1.0/24", 12, 2000), runner, clock,
                new EgressDailyRepair(repository, retention(), clock, maxRepairDays));
    }

    private static FakePrometheus oneFlow(long bytes) {
        return new FakePrometheus()
                .on(EgressQueries.BYTES_SENT, T, sample(bytes, "node", "mba1", "container_id", LITELLM,
                        "destination", "34.117.59.81:443", "actual_destination", "34.117.59.81:443"))
                .on(EgressQueries.AGENTS, T, sample(1, "node", "mba1"));
    }

    private List<String> daily() {
        return jdbc.sql("""
                        SELECT day || '|' || node || '|' || workload_key || '|' || destination_host || '|' || bytes_sent
                               || '|' || hourly_rows || '|' || is_new
                        FROM netmon.egress_flow_daily ORDER BY 1
                        """).query(String.class).list();
    }

    @Test
    void replacingAWindowRecomputesItsDayAndIsIdempotent() {
        insert("2026-09-24T07:00:00Z", 100);
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));

        collector(oneFlow(500), 32).collect();
        assertThat(daily()).containsExactly("2026-09-24|mba1|apps/litellm/litellm|34.117.59.81|600|2|true");

        // Re-collecting the same window with other numbers replaces it, and the day follows.
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));
        collector(oneFlow(700), 32).collect();
        assertThat(daily()).containsExactly("2026-09-24|mba1|apps/litellm/litellm|34.117.59.81|800|2|true");
    }

    @Test
    void anEmptiedWindowUpdatesItsDay() {
        insert("2026-09-24T09:00:00Z", 100);
        repository.recomputeDay(LocalDate.parse("2026-09-24"));
        assertThat(daily()).hasSize(1);
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));

        collector(new FakePrometheus().on(EgressQueries.AGENTS, T, sample(1, "node", "mba1")), 32).collect();

        assertThat(daily()).isEmpty();
    }

    @Test
    void isNewLooksAtTheSameDayAndThirtyDailyDays() {
        // Seen 20 days ago (only daily rows, raw already gone): known.
        dailyRow("2026-09-04", "34.117.59.81");
        // Seen only 31 days ago: outside the look-back, new.
        dailyRow("2026-08-24", "140.82.121.4");
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));
        FakePrometheus prometheus = new FakePrometheus()
                .on(EgressQueries.BYTES_SENT, T,
                        sample(1, "node", "mba1", "container_id", LITELLM, "destination", "34.117.59.81:443",
                                "actual_destination", "34.117.59.81:443"),
                        sample(1, "node", "mba1", "container_id", LITELLM, "destination", "140.82.121.4:443",
                                "actual_destination", "140.82.121.4:443"),
                        sample(1, "node", "mba1", "container_id", LITELLM, "destination", "140.82.121.9:443",
                                "actual_destination", "140.82.121.9:443"))
                .on(EgressQueries.AGENTS, T, sample(1, "node", "mba1"));
        // Seen earlier the same day (hourly row, not yet in any earlier daily row): known.
        insert("2026-09-24T03:00:00Z", 1, "140.82.121.9");

        collector(prometheus, 32).collect();

        assertThat(jdbc.sql("""
                        SELECT host(destination_ip) || '|' || is_new FROM netmon.egress_flow_snapshots
                        WHERE window_start = '2026-09-24T09:00:00Z' ORDER BY 1
                        """).query(String.class).list())
                .containsExactly("140.82.121.4|true", "140.82.121.9|false", "34.117.59.81|false");
    }

    @Test
    void repairFillsMissingDaysNewestFirstAndSkipsCollectionWhileALookBackDayIsMissing() {
        insert("2026-09-20T10:00:00Z", 10);
        insert("2026-09-22T10:00:00Z", 20);
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));

        // Only one recompute per run: 2026-09-22 (newest) is filled, 2026-09-20 is still missing. The run fails with
        // code partial (not a warning), so the freshness gauge stops; partial is no backoff code.
        assertThatThrownBy(() -> collector(oneFlow(500), 1).collect())
                .isInstanceOf(CollectorException.class)
                .satisfies(e -> assertThat(((CollectorException) e).code()).isEqualTo(ErrorCode.PARTIAL));
        assertThat(daily()).containsExactly("2026-09-22|mba1|apps/litellm/litellm|34.117.59.81|20|1|true");
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_snapshots WHERE window_start = '2026-09-24T09:00:00Z'")
                .query(Long.class).single()).isZero();
        assertThat(state.find(EgressCollector.NAME).orElseThrow().lastWindowEnd())
                .isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));

        // Next run: the last missing day is filled, then the window is collected.
        collector(oneFlow(500), 1).collect();
        assertThat(daily()).extracting(r -> r.substring(0, 10))
                .containsExactly("2026-09-20", "2026-09-22", "2026-09-24");
    }

    @Test
    void theSkippedRunCountsAsAFailureWithoutBackoff() {
        insert("2026-09-20T10:00:00Z", 10);
        insert("2026-09-22T10:00:00Z", 20);
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-24T09:00:00Z"));
        EgressCollector collector = collector(oneFlow(500), 1);

        assertThat(runner.run(collector)).isFalse();
        assertThat(state.find(EgressCollector.NAME).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(state.find(EgressCollector.NAME).orElseThrow().lastErrorCode()).isEqualTo("partial");

        // Not in backoff: the very next run repairs the last day and collects the window.
        assertThat(runner.run(collector)).isTrue();
        assertThat(state.find(EgressCollector.NAME).orElseThrow().consecutiveFailures()).isZero();
    }

    @Test
    void staleRecentDaysAreRecomputedAndOldCompleteDaysAreLeftAlone() {
        insert("2026-09-23T10:00:00Z", 10);
        insert("2026-09-15T10:00:00Z", 10);
        repository.recomputeDay(LocalDate.parse("2026-09-23"));
        repository.recomputeDay(LocalDate.parse("2026-09-15"));
        // A previous image rewrote hourly rows of both days after the daily rows were computed.
        jdbc.sql("UPDATE netmon.egress_flow_daily SET computed_at = now() - interval '1 hour'").update();
        jdbc.sql("UPDATE netmon.egress_flow_snapshots SET bytes_sent = 99").update();
        EgressDailyRepair repair = new EgressDailyRepair(repository, retention(), Clock.fixed(NOW, ZoneOffset.UTC), 32);

        // First run after startup: full-range stale check, both days recomputed.
        repair.repair();
        assertThat(daily()).extracting(r -> r.split("\\|")[4]).containsExactly("99", "99");

        // Later runs only look at the last 3 days.
        jdbc.sql("UPDATE netmon.egress_flow_daily SET computed_at = now() - interval '1 hour'").update();
        jdbc.sql("UPDATE netmon.egress_flow_snapshots SET bytes_sent = 7").update();
        repair.repair();
        assertThat(daily()).containsExactly("2026-09-15|mba1|apps/litellm/litellm|34.117.59.81|99|1|true",
                "2026-09-23|mba1|apps/litellm/litellm|34.117.59.81|7|1|true");
    }

    @Test
    void theFullStaleCheckRepeatsUntilAllDueDaysFitIntoOneRun() {
        insert("2026-09-10T10:00:00Z", 10);
        insert("2026-09-12T10:00:00Z", 10);
        repository.recomputeDay(LocalDate.parse("2026-09-10"));
        repository.recomputeDay(LocalDate.parse("2026-09-12"));
        jdbc.sql("UPDATE netmon.egress_flow_daily SET computed_at = now() - interval '1 hour'").update();
        jdbc.sql("UPDATE netmon.egress_flow_snapshots SET bytes_sent = 55").update();
        EgressDailyRepair repair = new EgressDailyRepair(repository, retention(), Clock.fixed(NOW, ZoneOffset.UTC), 1);

        repair.repair(); // newest stale day only (limit 1); the full check is not done yet
        assertThat(daily()).extracting(r -> r.split("\\|")[4]).containsExactly("10", "55");
        repair.repair(); // still a full-range check: the older stale day follows
        assertThat(daily()).extracting(r -> r.split("\\|")[4]).containsExactly("55", "55");
    }

    private void insert(String windowStart, long bytes) {
        insert(windowStart, bytes, "34.117.59.81");
    }

    private void insert(String windowStart, long bytes, String ip) {
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, namespace, pod,
                            container, workload, workload_key, destination, actual_destination, destination_ip,
                            destination_port, destination_scope, fqdn, bytes_sent, bytes_received, connects,
                            failed_connects, is_new, source)
                        VALUES (CAST(:ws AS timestamptz), CAST(:ws AS timestamptz) + interval '1 hour', 'mba1', :cid, 'apps',
                                'litellm-5d8f7c9b6-x2k9p', 'litellm', 'litellm', :key, :ip || ':443', :ip || ':443',
                                CAST(:ip AS inet), 443, 'external', NULL, :bytes, 0, 0, 0, true, 'prometheus-coroot')
                        """)
                .param("ws", windowStart).param("cid", LITELLM).param("key", KEY).param("ip", ip).param("bytes", bytes)
                .update();
    }

    private void dailyRow(String day, String ip) {
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_daily (day, node, workload_key, namespace, workload, container,
                            destination_host, destination_port, destination_scope, bytes_sent, bytes_received, connects,
                            failed_connects, first_window, is_new, hourly_rows)
                        VALUES (CAST(:day AS date), 'mba1', :key, 'apps', 'litellm', 'litellm', :ip, 443, 'external',
                                1, 1, 1, 0, CAST(:day AS date)::timestamp AT TIME ZONE 'UTC', true, 1)
                        """).param("day", day).param("key", KEY).param("ip", ip).update();
    }
}
