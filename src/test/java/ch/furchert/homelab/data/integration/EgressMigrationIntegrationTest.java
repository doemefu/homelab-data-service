package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.retention.RetentionJob;
import ch.furchert.homelab.data.netmon.retention.RetentionTarget;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V4__netmon_egress, V9__netmon_egress_daily (docs/060 §3.3, §3.4) and the NM-2 retention targets (§3.5, data-service#57). */
class EgressMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollectorRunner runner;
    @Autowired
    ObjectProvider<RetentionTarget> targets;

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    @Test
    void historyRecordsV4() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '4' AND description = 'netmon egress'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }

    @Test
    void naturalKeyIsUnique() {
        insert("'2026-09-24T09:00:00Z'", "'34.117.59.81:443'", "'34.117.59.81:443'", "'34.117.59.81'", "NULL", "'external'");
        assertThatThrownBy(() -> insert("'2026-09-24T09:00:00Z'", "'34.117.59.81:443'", "'34.117.59.81:443'",
                "'34.117.59.81'", "NULL", "'external'")).isInstanceOf(DataIntegrityViolationException.class);
        // Same destination with another actual_destination is another row.
        insert("'2026-09-24T09:00:00Z'", "'34.117.59.81:443'", "''", "'34.117.59.81'", "NULL", "'external'");
    }

    @Test
    void destinationHostIsTheIpOrTheName() {
        insert("'2026-09-24T09:00:00Z'", "'[2001:db8::1]:443'", "'[2001:db8::1]:443'", "'2001:db8::1'", "'v6.example.com'", "'external'");
        insert("'2026-09-24T09:00:00Z'", "'api.anthropic.com:443'", "''", "NULL", "'api.anthropic.com'", "'external'");
        assertThat(jdbc.sql("SELECT destination_host FROM netmon.egress_flow_snapshots ORDER BY destination_host")
                .query(String.class).list())
                .containsExactly("2001:db8::1", "api.anthropic.com");
    }

    @Test
    void checksRejectValuesOutsideTheContract() {
        // Neither an IP nor a name.
        assertThatThrownBy(() -> insert("'2026-09-24T09:00:00Z'", "'x:443'", "''", "NULL", "NULL", "'external'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("'2026-09-24T09:00:00Z'", "'1.2.3.4:443'", "''", "'1.2.3.4'", "NULL", "'internet'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Not hour-aligned.
        assertThatThrownBy(() -> insert("'2026-09-24T09:15:00Z'", "'1.2.3.4:443'", "''", "'1.2.3.4'", "NULL", "'external'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historyRecordsV9AndTheDailyTableStartsEmpty() {
        assertThat(jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '9' AND description = 'netmon egress daily'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_daily").query(Long.class).single()).isZero();
        // container is nullable, as in V4; destination_scope is part of the primary key.
        assertThat(jdbc.sql("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = 'netmon' AND table_name = 'egress_flow_daily' AND column_name = 'container'
                        """).query(String.class).single()).isEqualTo("YES");
        assertThat(jdbc.sql("""
                        SELECT string_agg(a.attname, ',' ORDER BY array_position(i.indkey::int2[], a.attnum))
                        FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                        WHERE i.indrelid = 'netmon.egress_flow_daily'::regclass AND i.indisprimary
                        """).query(String.class).single())
                .isEqualTo("day,workload_key,destination_host,destination_port,destination_scope,node");
    }

    @Test
    void dailyChecksRejectValuesOutsideTheContract() {
        assertThatThrownBy(() -> daily("'2026-09-24'", "'internet'", 1, "NULL", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> daily("'2026-09-24'", "'external'", 0, "NULL", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // fqdn and fqdn_window come together.
        assertThatThrownBy(() -> daily("'2026-09-24'", "'external'", 1, "'a.example.com'", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        daily("'2026-09-24'", "'external'", 1, "'a.example.com'", "'2026-09-24T09:00:00Z'");
    }

    @Test
    void rawRetentionKeeps8DaysOnlyForDaysWithDailyRowsAndRunsBeforeTheDailyTarget() {
        assertThat(targets.orderedStream().map(t -> t.table() + ":" + t.timeColumn() + ":" + t.days()).toList())
                .containsSubsequence("egress_flow_snapshots:window_start:8", "egress_flow_daily:day:32");
        // Ten and eleven days old; only the day of the first one has daily rows.
        insert("date_trunc('hour', now() - interval '10 days')", "'1.2.3.4:443'", "''", "'1.2.3.4'", "NULL", "'external'");
        insert("date_trunc('hour', now() - interval '11 days')", "'1.2.3.5:443'", "''", "'1.2.3.5'", "NULL", "'external'");
        insert("date_trunc('hour', now() - interval '7 days')", "'1.2.3.6:443'", "''", "'1.2.3.6'", "NULL", "'external'");
        daily("((now() - interval '10 days') AT TIME ZONE 'UTC')::date", "'external'", 1, "NULL", "NULL");
        // Daily rows: 33 days old goes, 31 days old stays.
        daily("((now() - interval '33 days') AT TIME ZONE 'UTC')::date", "'external'", 1, "NULL", "NULL");
        daily("((now() - interval '31 days') AT TIME ZONE 'UTC')::date", "'external'", 1, "NULL", "NULL");

        assertThat(runner.run(new RetentionJob(jdbc, targets.orderedStream().toList(), runner))).isTrue();

        assertThat(jdbc.sql("SELECT host(destination_ip) FROM netmon.egress_flow_snapshots ORDER BY 1")
                .query(String.class).list()).containsExactly("1.2.3.5", "1.2.3.6");
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_daily").query(Long.class).single()).isEqualTo(2L);
    }

    /** SQL fragments are fixed test literals. */
    private void daily(String day, String scope, int hourlyRows, String fqdn, String fqdnWindow) {
        jdbc.sql("""
                INSERT INTO netmon.egress_flow_daily (day, node, workload_key, container, destination_host, destination_port,
                    destination_scope, fqdn, fqdn_window, bytes_sent, bytes_received, connects, failed_connects,
                    first_window, is_new, hourly_rows)
                VALUES (%1$s, 'mba1', '/system.slice/k3s.service', 'k3s.service', '1.2.3.4', 443, %2$s, %4$s,
                        CAST(%5$s AS timestamptz), 1, 1, 1, 0, CAST(%1$s AS date)::timestamp AT TIME ZONE 'UTC', false, %3$s)
                ON CONFLICT DO NOTHING
                """.formatted(day, scope, hourlyRows, fqdn, fqdnWindow)).update();
    }

    /** SQL fragments are fixed test literals. */
    private void insert(String windowStart, String destination, String actual, String ip, String fqdn, String scope) {
        jdbc.sql("""
                INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, workload_key,
                    destination, actual_destination, destination_ip, destination_port, destination_scope, fqdn,
                    bytes_sent, bytes_received, connects, failed_connects, is_new, source)
                VALUES (%1$s, %1$s + interval '1 hour', 'mba1', '/system.slice/k3s.service', '/system.slice/k3s.service',
                        %2$s, %3$s, CAST(%4$s AS inet), 443, %6$s, %5$s, 0, 0, 0, 0, true, 'prometheus-coroot')
                """.formatted("CAST(" + windowStart + " AS timestamptz)", destination, actual, ip, fqdn, scope)).update();
    }
}
