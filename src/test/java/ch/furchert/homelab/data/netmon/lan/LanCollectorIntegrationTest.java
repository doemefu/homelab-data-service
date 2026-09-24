package ch.furchert.homelab.data.netmon.lan;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.LanProperties;
import ch.furchert.homelab.data.config.PrometheusProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.prometheus.PrometheusClient;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static ch.furchert.homelab.data.netmon.lan.FakePrometheus.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The {@code lan} collector (docs/060 §4.1, §4.6) against Postgres and a stubbed Prometheus. */
class LanCollectorIntegrationTest extends AbstractIntegrationTest {

    private static final Instant PREVIOUS_END = Instant.parse("2026-09-24T09:45:00Z");
    private static final Instant T = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant T_PLUS_4 = Instant.parse("2026-09-24T10:04:00Z");
    private static final Instant T_PLUS_12 = Instant.parse("2026-09-24T10:12:00Z");
    private static final Instant NEXT = Instant.parse("2026-09-24T10:15:00Z");
    private static final Instant NEXT_PLUS_4 = Instant.parse("2026-09-24T10:19:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    LanSnapshotRepository repository;
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

    private LanCollector collector(PrometheusClient prometheus, Instant now, int maxWindows) {
        return new LanCollector(prometheus, repository, enrichment, state, new LanProperties(maxWindows), runner,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static double seconds(Instant instant) {
        return instant.getEpochSecond();
    }

    /** raspi5 and mba1 both published bucket T; mba1 had no blocks and no SSH attempts in it. */
    private static FakePrometheus healthyWindow() {
        return new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4,
                        sample(seconds(T_PLUS_4) - 55, "node", "raspi5"), sample(seconds(T_PLUS_4) - 55, "node", "mba1"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4,
                        sample(seconds(T), "node", "raspi5"), sample(seconds(T), "node", "mba1"))
                .on(LanQueries.CONNECTIONS, T,
                        sample(2, "node", "raspi5", "dport", "1883", "src_ip", "192.168.1.50", "state", "ESTABLISHED"),
                        sample(1, "node", "mba1", "dport", "22", "src_ip", "10.42.0.0/16", "state", "ESTABLISHED"))
                .on(LanQueries.ufwBlocks(T), T_PLUS_4,
                        sample(4, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "TCP"),
                        sample(1, "node", "raspi5", "src_ip", "other", "dport", "0", "proto", "ICMP"))
                .on(LanQueries.sshAuth(T), T_PLUS_4,
                        sample(2, "node", "raspi5", "src_ip", "203.0.113.9", "outcome", "failed"),
                        sample(1, "node", "raspi5", "src_ip", "192.168.1.20", "outcome", "accepted"));
    }

    @Test
    void writesTheThreeTablesAndIsIdempotent() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = healthyWindow();

        assertThat(runner.run(collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32))).isTrue();

        List<Map<String, Object>> connections = rows("lan_connection_snapshots", "node, dport, src_ip, state, peak_connections");
        List<Map<String, Object>> ufw = rows("ufw_block_snapshots", "node, src_ip, dport, proto, blocks");
        List<Map<String, Object>> ssh = rows("ssh_auth_snapshots", "node, src_ip, outcome, attempts");
        assertThat(connections).extracting(r -> r.get("node") + "|" + r.get("dport") + "|" + r.get("src_ip") + "|"
                        + r.get("state") + "|" + r.get("peak_connections"))
                .containsExactly("mba1|22|10.42.0.0/16|ESTABLISHED|1", "raspi5|1883|192.168.1.50|ESTABLISHED|2");
        assertThat(ufw).extracting(r -> r.get("node") + "|" + r.get("src_ip") + "|" + r.get("dport") + "|"
                        + r.get("proto") + "|" + r.get("blocks"))
                .containsExactly("raspi5|203.0.113.9|23|TCP|4", "raspi5|other|0|ICMP|1");
        assertThat(ssh).extracting(r -> r.get("node") + "|" + r.get("src_ip") + "|" + r.get("outcome") + "|" + r.get("attempts"))
                .containsExactly("raspi5|192.168.1.20|accepted|1", "raspi5|203.0.113.9|failed|2");
        Map<String, Object> window = jdbc.sql("""
                        SELECT min(window_start) AS ws, max(window_end) AS we, min(source) AS source
                        FROM netmon.ufw_block_snapshots
                        """).query().singleRow();
        assertThat(window.get("ws")).isEqualTo(ts("2026-09-24T09:45:00Z"));
        assertThat(window.get("we")).isEqualTo(ts("2026-09-24T10:00:00Z"));
        assertThat(window.get("source")).isEqualTo("prometheus-textfile");
        assertThat(mark()).isEqualTo(T);
        assertThat(state.find(LanCollector.NAME).orElseThrow().lastErrorCode()).isNull();

        // Re-collect the same window: replaced per (window_start, node), not duplicated.
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        assertThat(runner.run(collector(healthyWindow(), Instant.parse("2026-09-24T10:05:00Z"), 32))).isTrue();
        assertThat(rows("lan_connection_snapshots", "node, dport, src_ip, state, peak_connections")).isEqualTo(connections);
        assertThat(rows("ufw_block_snapshots", "node, src_ip, dport, proto, blocks")).isEqualTo(ufw);
        assertThat(rows("ssh_auth_snapshots", "node, src_ip, outcome, attempts")).isEqualTo(ssh);
    }

    @Test
    void publishedNodeWithoutBucketSeriesGetsZeroRowsNotTheOldValues() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        jdbc.sql("""
                INSERT INTO netmon.ufw_block_snapshots (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'mba1', '198.51.100.3', 22, 'TCP', 9, 'prometheus-textfile')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ssh_auth_snapshots (window_start, window_end, node, src_ip, outcome, attempts, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'mba1', '198.51.100.3', 'failed', 3, 'prometheus-textfile')
                """).update();

        collector(healthyWindow(), Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(jdbc.sql("SELECT count(*) FROM netmon.ufw_block_snapshots WHERE node = 'mba1'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.ssh_auth_snapshots WHERE node = 'mba1'").query(Long.class).single()).isZero();
    }

    @Test
    void onlyPublicSourceIpsAreEnrichedAsLan() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        collector(healthyWindow(), Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        List<Map<String, Object>> enriched = jdbc.sql("""
                        SELECT host(ip) AS ip, array_to_string(seen_in, ',') AS seen_in, first_seen, last_seen, source
                        FROM netmon.ip_enrichment
                        """).query().listOfRows();
        assertThat(enriched).singleElement().satisfies(r -> {
            assertThat(r.get("ip")).isEqualTo("203.0.113.9");
            assertThat(r.get("seen_in")).isEqualTo("lan");
            assertThat(r.get("first_seen")).isEqualTo(ts("2026-09-24T09:45:00Z"));
            assertThat(r.get("last_seen")).isEqualTo(ts("2026-09-24T10:00:00Z"));
            assertThat(r.get("source")).isEqualTo("prometheus-textfile");
        });
    }

    @Test
    void expectedNodeNotYetPublishedHoldsTheMarkAndIsWrittenOnTheRetry() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"), sample(1, "node", "mba2"))
                // mba2 still shows the previous bucket at T+4m.
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"),
                        sample(seconds(PREVIOUS_END), "node", "mba2"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_12, sample(seconds(T), "node", "raspi5"), sample(seconds(T), "node", "mba2"))
                .on(LanQueries.ufwBlocks(T), T_PLUS_4, sample(1, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "TCP"))
                .on(LanQueries.ufwBlocks(T), T_PLUS_12,
                        sample(1, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "TCP"),
                        sample(5, "node", "mba2", "src_ip", "203.0.113.10", "dport", "22", "proto", "TCP"))
                // The next window is complete for both nodes.
                .on(LanQueries.EXPECTED_NODES, NEXT_PLUS_4, sample(1, "node", "raspi5"), sample(1, "node", "mba2"))
                .on(LanQueries.BUCKET_ENDS, NEXT_PLUS_4, sample(seconds(NEXT), "node", "raspi5"), sample(seconds(NEXT), "node", "mba2"));

        collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(mark()).isEqualTo(PREVIOUS_END);
        assertThat(jdbc.sql("SELECT node FROM netmon.ufw_block_snapshots").query(String.class).list()).containsExactly("raspi5");
        assertThat(prometheus.calls).noneMatch(c -> c.startsWith(T_PLUS_12.getEpochSecond() + " "));

        collector(prometheus, Instant.parse("2026-09-24T10:19:30Z"), 32).collect();

        assertThat(mark()).isEqualTo(NEXT);
        assertThat(jdbc.sql("""
                        SELECT node || '|' || blocks FROM netmon.ufw_block_snapshots
                        WHERE window_start = '2026-09-24T09:45:00Z' ORDER BY node
                        """).query(String.class).list())
                .containsExactly("mba2|5", "raspi5|1");
        // mba2's retry used the T+12m evaluation only for the bucket data.
        assertThat(prometheus.calls).contains(FakePrometheus.key(LanQueries.ufwBlocks(T), T_PLUS_12));
    }

    @Test
    void nodeThatNeverPublishesIsSkippedAfterTheRetry() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"), sample(1, "node", "mba2"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"),
                        sample(seconds(PREVIOUS_END), "node", "mba2"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_12, sample(seconds(T), "node", "raspi5"),
                        sample(seconds(PREVIOUS_END), "node", "mba2"))
                // In the next window mba2 is still stuck, and its retry point (10:27) has not come yet.
                .on(LanQueries.EXPECTED_NODES, NEXT_PLUS_4, sample(1, "node", "raspi5"), sample(1, "node", "mba2"))
                .on(LanQueries.BUCKET_ENDS, NEXT_PLUS_4, sample(seconds(NEXT), "node", "raspi5"),
                        sample(seconds(PREVIOUS_END), "node", "mba2"));

        assertThat(runner.run(collector(prometheus, Instant.parse("2026-09-24T10:19:30Z"), 32))).isTrue();

        assertThat(mark()).isEqualTo(T);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.ufw_block_snapshots WHERE node = 'mba2'").query(Long.class).single()).isZero();
        // The skipped node is visible in /status instead of disappearing silently.
        CollectorState row = state.find(LanCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isEqualTo("upstream");
        assertThat(row.lastError()).isEqualTo("CollectorWarning: 1" + LanCollector.UNPUBLISHED);
        assertThat(row.consecutiveFailures()).isZero();
    }

    @Test
    void catchUpThroughWindowsBeforeTheRolloutDoesNotWarn() {
        // First run: 48 h of empty history, but the latest evaluation point has a node.
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"));

        assertThat(runner.run(collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 4))).isTrue();

        CollectorState row = state.find(LanCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isNull();
        assertThat(row.lastWindowEnd()).isEqualTo(T.minusSeconds(48 * 3600).plusSeconds(4 * 900));
    }

    @Test
    void aBucketSeriesOfThePreviousWindowNeverLeaksIntoTheNextOne() {
        Instant earlier = PREVIOUS_END.minusSeconds(900);
        state.updateWindowEnd(LanCollector.NAME, earlier);
        Instant previousPlus4 = PREVIOUS_END.plusSeconds(240);
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, previousPlus4, sample(1, "node", "raspi5"))
                .on(LanQueries.BUCKET_ENDS, previousPlus4, sample(seconds(PREVIOUS_END), "node", "raspi5"))
                .on(LanQueries.ufwBlocks(PREVIOUS_END), previousPlus4,
                        sample(6, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "TCP"))
                // Window T: the guard moved on and no bucket series matches it.
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"));

        collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(mark()).isEqualTo(T);
        assertThat(jdbc.sql("""
                        SELECT to_char(window_start AT TIME ZONE 'UTC', 'HH24:MI') || '|' || blocks
                        FROM netmon.ufw_block_snapshots
                        """).query(String.class).list())
                .containsExactly("09:30|6");
    }

    @Test
    void ipv4MappedSourcesAreNormalisedAndMerged() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"))
                .on(LanQueries.ufwBlocks(T), T_PLUS_4,
                        sample(2, "node", "raspi5", "src_ip", "::ffff:203.0.113.9", "dport", "23", "proto", "TCP"),
                        sample(3, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "TCP"),
                        sample(1, "node", "raspi5", "src_ip", "2001:DB8:0:0:0:0:0:1", "dport", "22", "proto", "TCP"));

        collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(jdbc.sql("SELECT src_ip || '|' || blocks FROM netmon.ufw_block_snapshots ORDER BY src_ip")
                .query(String.class).list())
                .containsExactly("2001:db8::1|1", "203.0.113.9|5");
    }

    @Test
    void connectionsOfANodeThatIsNotExpectedAnyMoreAreStillWritten() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        // mba1 had connections during the window but was down (no series at all) at T+4m.
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"))
                .on(LanQueries.CONNECTIONS, T,
                        sample(3, "node", "mba1", "dport", "6443", "src_ip", "192.168.1.11", "state", "ESTABLISHED"));

        collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(mark()).isEqualTo(T);
        assertThat(jdbc.sql("SELECT node FROM netmon.lan_connection_snapshots").query(String.class).list()).containsExactly("mba1");
    }

    @Test
    void withoutAnyNodeTheRunSucceedsWithAnUpstreamWarningAndAdvances() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);

        assertThat(runner.run(collector(new FakePrometheus(), Instant.parse("2026-09-24T10:04:30Z"), 32))).isTrue();

        CollectorState row = state.find(LanCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isEqualTo("upstream");
        assertThat(row.lastError()).isEqualTo("CollectorWarning: " + LanCollector.NO_NODES);
        assertThat(row.consecutiveFailures()).isZero();
        assertThat(row.lastSuccessAt()).isNotNull();
        assertThat(row.lastWindowEnd()).isEqualTo(T);
    }

    @Test
    void prometheusFailureKeepsTheMark() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus();
        prometheus.failure = new CollectorException(ErrorCode.UPSTREAM, "Prometheus unreachable");

        assertThat(runner.run(collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32))).isFalse();

        CollectorState row = state.find(LanCollector.NAME).orElseThrow();
        assertThat(row.lastWindowEnd()).isEqualTo(PREVIOUS_END);
        assertThat(row.consecutiveFailures()).isEqualTo(1);
        assertThat(row.lastErrorCode()).isEqualTo("upstream");
    }

    @Test
    void firstRunStartsAt48HoursAndIsThrottled() {
        Instant now = Instant.parse("2026-09-24T10:04:30Z");
        runner.run(collector(new FakePrometheus(), now, 4));
        assertThat(mark()).isEqualTo(T.minusSeconds(48 * 3600).plusSeconds(4 * 900));
    }

    @Test
    void gapBeyond48HoursIsSkipped() {
        state.updateWindowEnd(LanCollector.NAME, Instant.parse("2026-09-20T00:00:00Z"));
        FakePrometheus prometheus = new FakePrometheus();

        runner.run(collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 2));

        assertThat(mark()).isEqualTo(Instant.parse("2026-09-22T10:30:00Z"));
        assertThat(prometheus.calls).noneMatch(c -> c.contains(Long.toString(Instant.parse("2026-09-21T00:04:00Z").getEpochSecond())));
    }

    @Test
    void nothingToDoBeforeTheFirstEvaluationPoint() {
        state.updateWindowEnd(LanCollector.NAME, T);
        FakePrometheus prometheus = new FakePrometheus();

        collector(prometheus, Instant.parse("2026-09-24T10:18:59Z"), 32).collect();

        assertThat(prometheus.calls).isEmpty();
        assertThat(mark()).isEqualTo(T);
    }

    @Test
    void seriesWithUnexpectedLabelsAreDroppedNotFatal() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus()
                .on(LanQueries.EXPECTED_NODES, T_PLUS_4, sample(1, "node", "raspi5"))
                .on(LanQueries.BUCKET_ENDS, T_PLUS_4, sample(seconds(T), "node", "raspi5"))
                .on(LanQueries.CONNECTIONS, T,
                        sample(1, "node", "raspi5", "dport", "abc", "src_ip", "192.168.1.5", "state", "ESTABLISHED"),
                        sample(1, "node", "raspi5", "dport", "22", "src_ip", "192.168.1.5", "state", "ESTABLISHED"))
                .on(LanQueries.ufwBlocks(T), T_PLUS_4,
                        sample(1, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "23", "proto", "SCTP"),
                        sample(Double.NaN, "node", "raspi5", "src_ip", "203.0.113.9", "dport", "24", "proto", "TCP"),
                        sample(2, "src_ip", "203.0.113.9", "dport", "25", "proto", "TCP"))
                .on(LanQueries.sshAuth(T), T_PLUS_4,
                        sample(1, "node", "raspi5", "src_ip", "203.0.113.9", "outcome", "weird"),
                        sample(1, "node", "raspi5", "src_ip", "203.0.113.9", "outcome", "invalid_user"));

        collector(prometheus, Instant.parse("2026-09-24T10:04:30Z"), 32).collect();

        assertThat(jdbc.sql("SELECT count(*) FROM netmon.lan_connection_snapshots").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.ufw_block_snapshots").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT outcome FROM netmon.ssh_auth_snapshots").query(String.class).list())
                .containsExactly("invalid_user");
        assertThat(mark()).isEqualTo(T);
    }

    @Test
    void endToEndOverHttpUsesTheContractQueriesAndTimes() {
        state.updateWindowEnd(LanCollector.NAME, PREVIOUS_END);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        String url = "http://prometheus.test:9090/api/v1/query";
        expect(server, url, LanQueries.EXPECTED_NODES, T_PLUS_4, "{\"node\":\"raspi5\"}", "1758708005");
        // Asked again after the loop, at the latest evaluation point (here the same window).
        expect(server, url, LanQueries.EXPECTED_NODES, T_PLUS_4, "{\"node\":\"raspi5\"}", "1758708005");
        expect(server, url, LanQueries.BUCKET_ENDS, T_PLUS_4, "{\"node\":\"raspi5\"}", Long.toString(T.getEpochSecond()));
        expect(server, url, LanQueries.CONNECTIONS, T,
                "{\"node\":\"raspi5\",\"dport\":\"1883\",\"src_ip\":\"192.168.1.50\",\"state\":\"ESTABLISHED\"}", "2");
        expect(server, url, LanQueries.ufwBlocks(T), T_PLUS_4,
                "{\"node\":\"raspi5\",\"src_ip\":\"203.0.113.9\",\"dport\":\"23\",\"proto\":\"TCP\"}", "4");
        expect(server, url, LanQueries.sshAuth(T), T_PLUS_4, null, null);
        PrometheusClient client = new PrometheusClient(builder.build(), new PrometheusProperties("http://prometheus.test:9090"),
                JsonMapper.builder().build());

        assertThat(runner.run(collector(client, Instant.parse("2026-09-24T10:04:30Z"), 32))).isTrue();

        server.verify();
        assertThat(mark()).isEqualTo(T);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.lan_connection_snapshots").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT blocks FROM netmon.ufw_block_snapshots").query(Integer.class).single()).isEqualTo(4);
    }

    private static void expect(MockRestServiceServer server, String url, String query, Instant time, String metric,
                               String value) {
        String result = metric == null ? "" : "{\"metric\":" + metric + ",\"value\":[" + time.getEpochSecond() + ",\"" + value + "\"]}";
        server.expect(requestTo(url))
                .andExpect(content().formData(MultiValueMap.fromSingleValue(Map.of(
                        "query", query, "time", Long.toString(time.getEpochSecond())))))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + result + "]}}",
                        MediaType.APPLICATION_JSON));
    }

    private Instant mark() {
        return state.find(LanCollector.NAME).map(CollectorState::lastWindowEnd).orElse(null);
    }

    private List<Map<String, Object>> rows(String table, String columns) {
        return jdbc.sql("SELECT " + columns + " FROM netmon." + table + " ORDER BY " + columns).query().listOfRows();
    }

    private static Object ts(String instant) {
        return java.sql.Timestamp.from(Instant.parse(instant));
    }
}
