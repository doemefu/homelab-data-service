package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V2__netmon_inbound (docs/060 §3.3, §3.4). */
class InboundMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM netmon.inbound_request_groups").update();
        jdbc.sql("DELETE FROM netmon.firewall_events").update();
        jdbc.sql("DELETE FROM netmon.ip_enrichment").update();
        jdbc.sql("DELETE FROM netmon.blocklist_entries").update();
        jdbc.sql("DELETE FROM netmon.blocklist_snapshots").update();
    }

    @Test
    void historyRecordsV2() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '2' AND description = 'netmon inbound'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }

    @Test
    void sampledIsGeneratedFromSampleInterval() {
        insertGroup("203.0.113.7", "/a", 1.0f);
        insertGroup("203.0.113.8", "/b", 10.0f);

        List<Boolean> sampled = jdbc.sql("SELECT sampled FROM netmon.inbound_request_groups ORDER BY client_ip")
                .query(Boolean.class).list();
        assertThat(sampled).containsExactly(false, true);
    }

    @Test
    void inboundNaturalKeyIsUnique() {
        insertGroup("203.0.113.7", "/a", 1.0f);
        assertThatThrownBy(() -> insertGroup("203.0.113.7", "/a", 1.0f))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void firewallNaturalKeyTreatsNullRuleIdAsEmpty() {
        String insert = """
                INSERT INTO netmon.firewall_events (occurred_at, ray_name, client_ip, action, security_source, rule_id, source)
                VALUES (now(), 'ray1', '203.0.113.7', 'block', 'firewallManaged', NULL, 'cloudflare-graphql')
                ON CONFLICT (ray_name, security_source, (coalesce(rule_id, '')), action) DO NOTHING
                """;
        assertThat(jdbc.sql(insert).update()).isEqualTo(1);
        assertThat(jdbc.sql(insert).update()).isZero();
    }

    @Test
    void seenInOnlyAcceptsKnownDataSets() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, source)
                        VALUES ('203.0.113.7', now(), now(), ARRAY['somewhere'], 'cloudflare-graphql')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void blocklistedMustMatchHits() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, blocklisted, source)
                        VALUES ('203.0.113.7', now(), now(), ARRAY['inbound'], true, 'cloudflare-graphql')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void blocklistEntriesReferenceASnapshotAndMatchByContainment() {
        Long snapshot = jdbc.sql("""
                        INSERT INTO netmon.blocklist_snapshots (list_name, fetched_at, source_url, outcome, source)
                        VALUES ('firehol-level1', now(), 'https://example.invalid', 'applied', 'firehol') RETURNING id
                        """).query(Long.class).single();
        jdbc.sql("INSERT INTO netmon.blocklist_entries (list_name, cidr, snapshot_id, source) VALUES ('firehol-level1', '198.51.100.0/24', ?, 'firehol')")
                .param(snapshot).update();

        Integer hits = jdbc.sql("SELECT count(*) FROM netmon.blocklist_entries WHERE cidr >>= '198.51.100.9'::inet")
                .query(Integer.class).single();
        assertThat(hits).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO netmon.blocklist_entries (list_name, cidr, snapshot_id, source) VALUES ('firehol-level1', '192.0.2.0/24', -1, 'firehol')")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertGroup(String ip, String path, float sampleInterval) {
        jdbc.sql("""
                        INSERT INTO netmon.inbound_request_groups
                            (window_start, window_end, is_final, client_ip, host, method, path, status, request_count,
                             sample_interval, source)
                        VALUES ('2026-09-23T10:00:00Z', '2026-09-23T11:00:00Z', true, CAST(? AS inet), 'furchert.ch', 'GET',
                                ?, 200, 1, ?, 'cloudflare-graphql')
                        """)
                .param(ip).param(path).param(sampleInterval)
                .update();
    }
}
