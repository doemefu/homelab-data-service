package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V1__netmon_baseline (docs/060 §3.1, §3.3, §3.4); V2 has its own test class. */
class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void netmonSchemaExists() {
        Integer count = jdbc.sql("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'netmon'")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void netmonHoldsTheV1ToV4TablesOnly() {
        // V1 = collector_state; V2 (NM-1) = the inbound tables; V3 (NM-3) = the LAN tables; V4 (NM-2) = egress;
        // NM-4 follows (docs/060 §3.4).
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'netmon'")
                .query(String.class).list();
        assertThat(tables).containsExactlyInAnyOrder("collector_state", "inbound_request_groups", "firewall_events",
                "ip_enrichment", "blocklist_snapshots", "blocklist_entries", "lan_connection_snapshots",
                "ufw_block_snapshots", "ssh_auth_snapshots", "egress_flow_snapshots");
    }

    @Test
    void collectorStateHasContractColumns() {
        Map<String, String> columns = jdbc.sql("""
                        SELECT column_name, data_type || ':' || is_nullable AS spec
                        FROM information_schema.columns
                        WHERE table_schema = 'netmon' AND table_name = 'collector_state'
                        """)
                .query((rs, i) -> Map.entry(rs.getString("column_name"), rs.getString("spec")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(columns).containsExactlyInAnyOrderEntriesOf(Map.of(
                "collector", "text:NO",
                "last_window_end", "timestamp with time zone:YES",
                "cursor", "text:YES",
                "last_attempt_at", "timestamp with time zone:YES",
                "last_success_at", "timestamp with time zone:YES",
                "consecutive_failures", "integer:NO",
                "last_error", "text:YES",
                "last_error_code", "text:YES"));
    }

    @Test
    void collectorIsPrimaryKey() {
        String pkColumn = jdbc.sql("""
                        SELECT kcu.column_name
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                          ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                        WHERE tc.table_schema = 'netmon' AND tc.table_name = 'collector_state'
                          AND tc.constraint_type = 'PRIMARY KEY'
                        """)
                .query(String.class).single();
        assertThat(pkColumn).isEqualTo("collector");
    }

    @Test
    void consecutiveFailuresDefaultsToZero() {
        jdbc.sql("INSERT INTO netmon.collector_state (collector) VALUES ('migration-test-default') ON CONFLICT DO NOTHING").update();
        Integer failures = jdbc.sql("SELECT consecutive_failures FROM netmon.collector_state WHERE collector = 'migration-test-default'")
                .query(Integer.class).single();
        assertThat(failures).isZero();
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = 'migration-test-default'").update();
    }

    @Test
    void lastErrorCodeOnlyAcceptsContractValues() {
        assertThatThrownBy(() -> jdbc.sql(
                        "INSERT INTO netmon.collector_state (collector, last_error_code) VALUES ('migration-test-code', 'some message')")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historyTableLivesInPublicAndRecordsV1() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '1' AND description = 'netmon baseline'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }
}
