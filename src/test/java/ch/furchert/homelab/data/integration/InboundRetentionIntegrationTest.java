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
import org.springframework.jdbc.core.simple.JdbcClient;


import static org.assertj.core.api.Assertions.assertThat;

/** NM-1 retention targets (docs/060 §3.3, §3.5), including the FK-safe blocklist_snapshots exception. */
class InboundRetentionIntegrationTest extends AbstractIntegrationTest {

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
    void registersTheFourNm1TablesWithContractDefaults() {
        assertThat(targets.orderedStream().map(t -> t.table() + ":" + t.timeColumn() + ":" + t.days()).toList())
                .contains("inbound_request_groups:window_start:90", "firewall_events:occurred_at:180",
                        "ip_enrichment:last_seen:180", "blocklist_snapshots:fetched_at:30");
    }

    @Test
    void deletesExpiredRowsButKeepsSnapshotsStillReferencedByEntries() {
        long referenced = snapshot("applied", 60);
        long unreferencedOld = snapshot("unchanged", 45);
        long recent = snapshot("unchanged", 1);
        jdbc.sql("INSERT INTO netmon.blocklist_entries (list_name, cidr, snapshot_id, source) VALUES ('firehol-level1', '198.51.100.0/24', ?, 'firehol')")
                .param(referenced).update();
        jdbc.sql("""
                INSERT INTO netmon.inbound_request_groups (window_start, window_end, is_final, client_ip, host, method, path,
                    status, request_count, sample_interval, source)
                VALUES (now() - interval '91 days', now() - interval '91 days' + interval '1 hour', true, '203.0.113.7',
                        'furchert.ch', 'GET', '/', 200, 1, 1, 'cloudflare-graphql'),
                       (now() - interval '89 days', now() - interval '89 days' + interval '1 hour', true, '203.0.113.7',
                        'furchert.ch', 'GET', '/', 200, 1, 1, 'cloudflare-graphql')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, source)
                VALUES ('203.0.113.7', now() - interval '200 days', now() - interval '181 days', ARRAY['inbound'], 'cloudflare-graphql'),
                       ('203.0.113.8', now() - interval '200 days', now() - interval '1 day', ARRAY['inbound'], 'cloudflare-graphql')
                """).update();

        RetentionJob job = new RetentionJob(jdbc, targets.orderedStream().toList(), runner);
        assertThat(runner.run(job)).isTrue();

        assertThat(jdbc.sql("SELECT id FROM netmon.blocklist_snapshots ORDER BY id").query(Long.class).list())
                .containsExactly(referenced, recent)
                .doesNotContain(unreferencedOld);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.inbound_request_groups").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT host(ip) FROM netmon.ip_enrichment").query(String.class).list())
                .containsExactly("203.0.113.8");
    }

    private long snapshot(String outcome, int daysAgo) {
        return jdbc.sql("""
                        INSERT INTO netmon.blocklist_snapshots (list_name, fetched_at, source_url, outcome, source)
                        VALUES ('firehol-level1', now() - make_interval(days => ?), 'https://example.invalid', ?, 'firehol')
                        RETURNING id
                        """)
                .param(daysAgo).param(outcome)
                .query(Long.class).single();
    }

}
