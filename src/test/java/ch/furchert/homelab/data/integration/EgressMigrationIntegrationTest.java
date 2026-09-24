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

/** V4__netmon_egress (docs/060 §3.3, §3.4) and the NM-2 retention target (§3.5). */
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
    void retentionKeeps30Days() {
        assertThat(targets.orderedStream().map(t -> t.table() + ":" + t.timeColumn() + ":" + t.days()).toList())
                .contains("egress_flow_snapshots:window_start:30");
        insert("date_trunc('hour', now() - interval '31 days')", "'1.2.3.4:443'", "''", "'1.2.3.4'", "NULL", "'external'");
        insert("date_trunc('hour', now() - interval '29 days')", "'1.2.3.4:443'", "''", "'1.2.3.4'", "NULL", "'external'");

        assertThat(runner.run(new RetentionJob(jdbc, targets.orderedStream().toList(), runner))).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_snapshots").query(Long.class).single()).isEqualTo(1L);
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
