package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.NetmonTables;
import ch.furchert.homelab.data.support.RawEventRows;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical WA-5 runbook SQL in {@code DEPLOYMENT.md} (#56 comment 4; homelab#351 ruling 3) runs as written against
 * the real schema: every {@code ```sql} block starting with {@code -- runbook:<name>} is executed with the example day
 * 2026-11-05, and its effect is checked.
 */
class RunbookSqlIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-05");
    private static final Pattern BLOCK = Pattern.compile("```sql\\n(-- runbook:([a-z-]+)\\n.*?)```", Pattern.DOTALL);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;

    Map<String, String> runbooks;

    @BeforeEach
    void setUp() throws IOException {
        WebAnalyticsTables.clear(jdbc);
        NetmonTables.clear(jdbc);
        runbooks = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(Files.readString(Path.of("DEPLOYMENT.md")));
        while (m.find()) {
            runbooks.put(m.group(2), m.group(1));
        }
        assertThat(runbooks).containsOnlyKeys("purge-day", "reset-days", "remark-days", "egress-parity",
                "egress-isnew-parity");
        AggregateRows aggregates = new AggregateRows(jdbc);
        for (LocalDate d = DAY.minusDays(2); !d.isAfter(DAY.plusDays(2)); d = d.plusDays(1)) {
            aggregates.rollupDay(d, true);
        }
        RawEventRows raw = new RawEventRows(jdbc);
        UUID visitor = UUID.randomUUID();
        raw.pageview(visitor, zurich("2026-11-05T00:10:00"), "/");   // inside D (Zurich)
        raw.pageview(visitor, zurich("2026-11-05T23:50:00"), "/it"); // inside D
        raw.pageview(visitor, zurich("2026-11-04T23:50:00"), "/");   // D − 1
        raw.pageview(visitor, zurich("2026-11-06T00:10:00"), "/");   // D + 1
    }

    @Test
    void purgeDayDeletesTheZurichDayAndTheThreeRollupRows() throws Exception {
        run("purge-day");
        assertThat(count("SELECT count(*) FROM webanalytics.events")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT day FROM webanalytics.rollup_day ORDER BY day").query(LocalDate.class).list())
                .containsExactly(DAY.minusDays(2), DAY.plusDays(2));
    }

    @Test
    void resetDaysDeletesOnlyTheThreeRollupRows() throws Exception {
        run("reset-days");
        assertThat(count("SELECT count(*) FROM webanalytics.events")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM webanalytics.rollup_day")).isEqualTo(2);
    }

    @Test
    void remarkDaysSetsTheTwoDaysProvisional() throws Exception {
        run("remark-days");
        assertThat(jdbc.sql("SELECT day FROM webanalytics.rollup_day WHERE NOT final ORDER BY day")
                .query(LocalDate.class).list()).containsExactly(DAY, DAY.plusDays(1));
    }

    @Test
    void egressParityComparesOnlyCompletedDaysEntirelyInsideTheRawRetention() {
        // The oldest day (30 days back) is partial in both tables and must not be compared; two recent days match,
        // one recent day has a wrong daily sum.
        egressRow("now() - interval '30 days'");
        egressRow("now() - interval '3 days'");
        egressRow("now() - interval '2 days'");
        jdbc.sql("""
                INSERT INTO netmon.egress_flow_daily (day, node, workload_key, container, destination_host,
                    destination_port, destination_scope, bytes_sent, bytes_received, connects, failed_connects,
                    first_window, is_new, hourly_rows)
                SELECT (window_start AT TIME ZONE 'UTC')::date, node, workload_key, max(container), destination_host,
                       destination_port, destination_scope, sum(bytes_sent), sum(bytes_received), sum(connects),
                       sum(failed_connects), min(window_start), bool_or(is_new), count(*)
                FROM netmon.egress_flow_snapshots
                GROUP BY 1, node, workload_key, destination_host, destination_port, destination_scope
                """).update();
        jdbc.sql("UPDATE netmon.egress_flow_daily SET bytes_sent = bytes_sent + 1 "
                + "WHERE day = ((now() - interval '2 days') AT TIME ZONE 'UTC')::date").update();

        java.util.List<String> rows = jdbc.sql(runbooks.get("egress-parity"))
                .query((rs, i) -> rs.getObject("day", LocalDate.class) + "|" + rs.getBoolean("equal")).list();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(rows).containsExactly(today.minusDays(3) + "|true", today.minusDays(2) + "|false");

        // is_new parity: only rows whose 30-day look-back lies fully inside the hourly rows are compared. A wrong flag
        // on the 3-days-old row (look-back reaches before the oldest hourly row) is ignored; one on a current row counts.
        assertThat(jdbc.sql(runbooks.get("egress-isnew-parity")).query(Long.class).single()).isZero();
        jdbc.sql("UPDATE netmon.egress_flow_snapshots SET is_new = true "
                + "WHERE window_start = date_trunc('hour', now() - interval '3 days')").update();
        egressRow("now()");
        jdbc.sql("UPDATE netmon.egress_flow_snapshots SET is_new = true WHERE window_start = date_trunc('hour', now())")
                .update();
        assertThat(jdbc.sql(runbooks.get("egress-isnew-parity")).query(Long.class).single()).isEqualTo(1L);
    }

    /** SQL fragment is a fixed test literal. */
    private void egressRow(String windowStart) {
        jdbc.sql("""
                INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, workload_key,
                    destination, actual_destination, destination_ip, destination_port, destination_scope, fqdn,
                    bytes_sent, bytes_received, connects, failed_connects, is_new, source)
                VALUES (date_trunc('hour', %1$s), date_trunc('hour', %1$s) + interval '1 hour', 'mba1',
                        '/system.slice/k3s.service', '/system.slice/k3s.service', '1.2.3.4:443', '1.2.3.4:443',
                        '1.2.3.4', 443, 'external', NULL, 10, 10, 1, 0, %2$s, 'prometheus-coroot')
                """.formatted(windowStart, windowStart.contains("30 days") ? "true" : "false")).update();
    }

    private void run(String name) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(runbooks.get(name));
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
