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

/** V3__netmon_lan (docs/060 §3.3, §3.4) and the NM-3 retention targets (§3.5). */
class LanMigrationIntegrationTest extends AbstractIntegrationTest {

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
    void historyRecordsV3() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '3' AND description = 'netmon lan'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }

    @Test
    void naturalKeysAreUnique() {
        connection("192.168.1.50", "ESTABLISHED");
        assertThatThrownBy(() -> connection("192.168.1.50", "ESTABLISHED")).isInstanceOf(DataIntegrityViolationException.class);
        ufw("203.0.113.9", "TCP");
        assertThatThrownBy(() -> ufw("203.0.113.9", "TCP")).isInstanceOf(DataIntegrityViolationException.class);
        ssh("failed");
        assertThatThrownBy(() -> ssh("failed")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checksRejectValuesOutsideTheContract() {
        assertThatThrownBy(() -> ufw("203.0.113.9", "SCTP")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> ssh("maybe")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO netmon.lan_connection_snapshots (window_start, window_end, node, dport, src_ip, state,
                    peak_connections, source)
                VALUES ('2026-09-24T09:40:00Z', '2026-09-24T09:55:00Z', 'raspi5', 22, 'other', 'ESTABLISHED', 1, 'prometheus-textfile')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO netmon.ssh_auth_snapshots (window_start, window_end, node, src_ip, outcome, attempts, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:45:00Z', 'raspi5', 'other', 'failed', 1, 'prometheus-textfile')
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void retentionKeepsConnections30AndBuckets90Days() {
        assertThat(targets.orderedStream().map(t -> t.table() + ":" + t.timeColumn() + ":" + t.days()).toList())
                .contains("lan_connection_snapshots:window_start:30", "ufw_block_snapshots:window_start:90",
                        "ssh_auth_snapshots:window_start:90");
        for (int days : new int[]{31, 29}) {
            jdbc.sql("""
                    INSERT INTO netmon.lan_connection_snapshots (window_start, window_end, node, dport, src_ip, state,
                        peak_connections, source)
                    VALUES (date_bin('15 minutes', now() - make_interval(days => :days), 'epoch'),
                            date_bin('15 minutes', now() - make_interval(days => :days), 'epoch') + interval '15 minutes',
                            'raspi5', 22, 'other', 'ESTABLISHED', 1, 'prometheus-textfile')
                    """).param("days", days).update();
        }
        for (int days : new int[]{91, 89}) {
            jdbc.sql("""
                    INSERT INTO netmon.ufw_block_snapshots (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                    VALUES (date_bin('15 minutes', now() - make_interval(days => :days), 'epoch'),
                            date_bin('15 minutes', now() - make_interval(days => :days), 'epoch') + interval '15 minutes',
                            'raspi5', 'other', 0, 'TCP', 1, 'prometheus-textfile')
                    """).param("days", days).update();
            jdbc.sql("""
                    INSERT INTO netmon.ssh_auth_snapshots (window_start, window_end, node, src_ip, outcome, attempts, source)
                    VALUES (date_bin('15 minutes', now() - make_interval(days => :days), 'epoch'),
                            date_bin('15 minutes', now() - make_interval(days => :days), 'epoch') + interval '15 minutes',
                            'raspi5', 'other', 'failed', 1, 'prometheus-textfile')
                    """).param("days", days).update();
        }

        assertThat(runner.run(new RetentionJob(jdbc, targets.orderedStream().toList(), runner))).isTrue();

        for (String table : new String[]{"lan_connection_snapshots", "ufw_block_snapshots", "ssh_auth_snapshots"}) {
            assertThat(jdbc.sql("SELECT count(*) FROM netmon." + table).query(Long.class).single()).as(table).isEqualTo(1L);
        }
    }

    private void connection(String srcIp, String state) {
        jdbc.sql("""
                INSERT INTO netmon.lan_connection_snapshots (window_start, window_end, node, dport, src_ip, state,
                    peak_connections, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'raspi5', 1883, ?, ?, 1, 'prometheus-textfile')
                """).param(srcIp).param(state).update();
    }

    private void ufw(String srcIp, String proto) {
        jdbc.sql("""
                INSERT INTO netmon.ufw_block_snapshots (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'raspi5', ?, 23, ?, 1, 'prometheus-textfile')
                """).param(srcIp).param(proto).update();
    }

    private void ssh(String outcome) {
        jdbc.sql("""
                INSERT INTO netmon.ssh_auth_snapshots (window_start, window_end, node, src_ip, outcome, attempts, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'raspi5', '203.0.113.9', ?, 1, 'prometheus-textfile')
                """).param(outcome).update();
    }
}
