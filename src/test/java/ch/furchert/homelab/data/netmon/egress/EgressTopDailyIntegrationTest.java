package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.RetentionProperties;
import ch.furchert.homelab.data.netmon.api.EgressDtos.EgressTopItem;
import ch.furchert.homelab.data.netmon.api.EgressQueryRepository;
import ch.furchert.homelab.data.netmon.api.TimeWindow;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /egress/top} over hourly and daily rows (docs/060 §7.2 after data-service#57), "now" = 2026-09-24T12:00Z, raw
 * retention 8 days, so the raw cut-off is 2026-09-17T00:00Z: a window starting at or after now − 8 d reads the hourly
 * rows only; an older one reads whole UTC days from {@code egress_flow_daily} before the cut-off (a partial first or
 * last day is left out) and the hourly rows from the cut-off on. For whole days the answer equals the hourly one.
 */
class EgressTopDailyIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    EgressSnapshotRepository repository;

    EgressQueryRepository queries;

    private static RetentionProperties retention(int rawDays) {
        return new RetentionProperties(90, 180, 180, 30, 30, 90, 90, rawDays, 180, 32);
    }

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        queries = new EgressQueryRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), retention(8));
        // litellm → 34.117.59.81 every day 09-10..09-24 on mba1, and on raspi5 on three days (node by hourly rows).
        for (int d = 10; d <= 24; d++) {
            String day = "2026-09-" + d;
            row(day + "T08:00:00Z", "mba1", "34.117.59.81", d < 15 ? "old.example.com" : null, 100, d == 10);
            row(day + "T09:00:00Z", "mba1", "34.117.59.81", null, 100, false);
            if (d % 5 == 0) {
                row(day + "T10:00:00Z", "raspi5", "34.117.59.81", null, 10, false);
            }
        }
        row("2026-09-23T11:00:00Z", "raspi5", "34.117.59.81", "new.example.com", 1, false);
        // Seen only on the afternoon of 2026-09-10, and only on the morning of 2026-09-13.
        row("2026-09-10T16:00:00Z", "mba1", "140.82.121.4", "github.com", 5, true);
        row("2026-09-13T08:00:00Z", "mba1", "140.82.121.3", null, 5, true);
        for (LocalDate d = LocalDate.parse("2026-09-10"); !d.isAfter(LocalDate.parse("2026-09-24")); d = d.plusDays(1)) {
            repository.recomputeDay(d);
        }
    }

    @Test
    void wholeDaysFromDailyRowsEqualTheHourlyAnswer() {
        TimeWindow window = new TimeWindow(Instant.parse("2026-09-10T00:00:00Z"), NOW);
        List<EgressTopItem> union = queries.top(window, null, null, null, 50);
        assertThat(union).isEqualTo(queries.topFromHourlyRows(window, null, null, null, 50));
        EgressTopItem litellm = union.stream().filter(i -> "34.117.59.81".equals(i.destinationIp())).findFirst()
                .orElseThrow();
        assertThat(litellm.node()).isEqualTo("mba1");
        assertThat(litellm.fqdn()).isEqualTo("new.example.com");
        assertThat(litellm.bytesSent()).isEqualTo(15 * 200 + 3 * 10 + 1);
        assertThat(litellm.isNew()).isTrue();
        assertThat(litellm.firstSeenInWindow()).isEqualTo(Instant.parse("2026-09-10T08:00:00Z"));
    }

    @Test
    void aPartialFirstDayIsLeftOut() {
        Instant from = Instant.parse("2026-09-10T15:00:00Z");
        List<EgressTopItem> union = queries.top(new TimeWindow(from, NOW), null, null, null, 50);
        assertThat(union).extracting(EgressTopItem::destinationIp).doesNotContain("140.82.121.4");
        assertThat(union).allSatisfy(i -> assertThat(i.firstSeenInWindow()).isAfterOrEqualTo(from));
        EgressTopItem litellm = union.stream().filter(i -> "34.117.59.81".equals(i.destinationIp())).findFirst()
                .orElseThrow();
        assertThat(litellm.bytesSent()).isEqualTo(14 * 200 + 2 * 10 + 1);
        assertThat(litellm.isNew()).isFalse(); // the only new hourly row was on the left-out day
    }

    @Test
    void aWindowBeforeTheCutOffEndingMidDayCountsOnlyTheWholeDaysInside() {
        TimeWindow window = new TimeWindow(Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-13T12:00:00Z"));
        List<EgressTopItem> union = queries.top(window, null, null, null, 50);
        assertThat(union).extracting(EgressTopItem::destinationIp)
                .containsExactly("34.117.59.81", "140.82.121.4"); // 140.82.121.3 is on the left-out 09-13
        assertThat(union.getFirst().bytesSent()).isEqualTo(3 * 200 + 10);
        assertThat(union.getFirst().fqdn()).isEqualTo("old.example.com");
    }

    @Test
    void aWindowStartingAtNowMinusTheRawRetentionReadsTheHourlyRowsOnly() {
        TimeWindow atBoundary = new TimeWindow(Instant.parse("2026-09-16T12:00:00Z"), NOW);
        assertThat(queries.top(atBoundary, null, null, null, 50))
                .isEqualTo(queries.topFromHourlyRows(atBoundary, null, null, null, 50));
        // Delete the daily rows: the hourly path does not need them.
        jdbc.sql("DELETE FROM netmon.egress_flow_daily").update();
        assertThat(queries.top(atBoundary, null, null, null, 50)).isNotEmpty();
        // One hour earlier the window takes the union path, which now misses the daily days.
        TimeWindow older = new TimeWindow(Instant.parse("2026-09-15T00:00:00Z"), NOW);
        assertThat(queries.top(older, null, null, null, 50).getFirst().bytesSent())
                .isLessThan(queries.topFromHourlyRows(older, null, null, null, 50).getFirst().bytesSent());
    }

    @Test
    void withTheStagingOverrideOf30DaysEveryAllowedWindowReadsTheHourlyRows() {
        EgressQueryRepository staging = new EgressQueryRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), retention(30));
        jdbc.sql("DELETE FROM netmon.egress_flow_daily").update();
        TimeWindow thirtyDays = new TimeWindow(NOW.minusSeconds(30L * 24 * 3600), NOW);
        assertThat(staging.top(thirtyDays, null, null, null, 50))
                .isEqualTo(staging.topFromHourlyRows(thirtyDays, null, null, null, 50));
    }

    private void row(String windowStart, String node, String ip, String fqdn, long bytes, boolean isNew) {
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, namespace, pod,
                            container, workload, workload_key, destination, actual_destination, destination_ip,
                            destination_port, destination_scope, fqdn, bytes_sent, bytes_received, connects,
                            failed_connects, is_new, source)
                        VALUES (CAST(:ws AS timestamptz), CAST(:ws AS timestamptz) + interval '1 hour', :node,
                                '/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm', 'apps', 'litellm-5d8f7c9b6-x2k9p', 'litellm',
                                'litellm', 'apps/litellm/litellm', :ip || ':443', :ip || ':443', CAST(:ip AS inet), 443,
                                'external', :fqdn, :bytes, :bytes, 1, 0, :isNew, 'prometheus-coroot')
                        """)
                .param("ws", windowStart).param("node", node).param("ip", ip).param("fqdn", fqdn).param("bytes", bytes)
                .param("isNew", isNew).update();
    }
}
