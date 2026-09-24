package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.EgressProperties;
import ch.furchert.homelab.data.config.PrometheusProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.lan.FakePrometheus;
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

/** The {@code egress} collector (docs/060 §4.1, §4.6) against Postgres and a stubbed Prometheus. */
class EgressCollectorIntegrationTest extends AbstractIntegrationTest {

    private static final Instant PREVIOUS_END = Instant.parse("2026-09-24T09:00:00Z");
    private static final Instant T = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-24T10:07:30Z");

    private static final String LITELLM = "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm";
    private static final String FURCHERT = "/k8s/apps/furchert-ch-7fb9c6d48-m4n5q/furchert-ch";
    private static final String N8N = "/k8s/apps/n8n-6c8d9f7b5-q8w2z/n8n";
    private static final String FLUX = "/k8s/flux-system/source-controller-6b8d9f7c5-x7kqp/manager";
    private static final String K3S = "/system.slice/k3s.service";

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

    private EgressCollector collector(PrometheusClient prometheus, Instant now, int maxWindows, int maxRows) {
        return new EgressCollector(prometheus, repository, enrichment, state,
                new EgressProperties("10.42.0.0/16", "10.43.0.0/16", "192.168.1.0/24", maxWindows, maxRows), runner,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private EgressCollector collector(PrometheusClient prometheus) {
        return collector(prometheus, NOW, 12, 2000);
    }

    private static String[] flow(String node, String containerId, String destination, String actual) {
        return actual == null
                ? new String[]{"node", node, "container_id", containerId, "destination", destination}
                : new String[]{"node", node, "container_id", containerId, "destination", destination, "actual_destination", actual};
    }

    /** One hour of realistic traffic on two nodes; see the assertions for what each series exercises. */
    private static FakePrometheus busyWindow() {
        return new FakePrometheus()
                .on(EgressQueries.BYTES_SENT, T,
                        // Name-grouped external destination: no actual_destination, no IP.
                        sample(1200.4, flow("mba1", LITELLM, "api.anthropic.com:443", null)),
                        sample(500, flow("mba1", LITELLM, "34.117.59.81:443", "34.117.59.81:443")),
                        // Service → pod (DNAT).
                        sample(300, flow("mba1", FURCHERT, "10.43.0.10:8080", "10.42.1.7:8080")),
                        sample(50, flow("raspi5", K3S, "140.82.121.4:443", "140.82.121.4:443")))
                .on(EgressQueries.BYTES_RECEIVED, T,
                        sample(9000, flow("mba1", LITELLM, "api.anthropic.com:443", null)))
                .on(EgressQueries.CONNECTS, T,
                        sample(3, flow("mba1", LITELLM, "api.anthropic.com:443", null)),
                        sample(1, flow("mba1", LITELLM, "34.117.59.81:443", "34.117.59.81:443")))
                .on(EgressQueries.FAILED_CONNECTS, T,
                        // Unambiguous: exactly one row with this destination.
                        sample(2, flow("mba1", FURCHERT, "10.43.0.10:8080", null)),
                        // No success row at all.
                        sample(1, flow("mba1", LITELLM, "203.0.113.50:443", null)),
                        // Ambiguous: two backends behind the same Service.
                        sample(4, flow("raspi5", N8N, "10.43.0.20:5678", null)))
                .on(EgressQueries.FQDN, T,
                        sample(1, "ip", "34.117.59.81", "fqdn", "b.example.com"),
                        sample(1, "ip", "34.117.59.81", "fqdn", "a.example.com."),
                        sample(1, "ip", "140.82.121.4", "fqdn", "github.com"))
                .on(EgressQueries.PRESENCE, T,
                        sample(1, flow("mba1", LITELLM, "api.anthropic.com:443", null)),
                        // First sample inside the window: only the presence query sees it.
                        sample(1, flow("raspi5", FLUX, "140.82.121.3:443", "140.82.121.3:443")),
                        sample(1, flow("raspi5", N8N, "10.43.0.20:5678", "10.42.1.8:5678")),
                        sample(1, flow("raspi5", N8N, "10.43.0.20:5678", "10.42.2.9:5678")))
                .on(EgressQueries.AGENTS, T, sample(10, "node", "raspi5"), sample(20, "node", "mba1"));
    }

    private List<String> flows() {
        return jdbc.sql("""
                        SELECT node || '|' || coalesce(namespace, '-') || '|' || coalesce(workload, '-') || '|'
                               || coalesce(container, '-') || '|' || destination || '|' || actual_destination || '|'
                               || coalesce(host(destination_ip), '-') || '|' || destination_port || '|'
                               || destination_scope || '|' || coalesce(fqdn, '-') || '|' || bytes_sent || '|'
                               || bytes_received || '|' || connects || '|' || failed_connects || '|' || is_new
                        FROM netmon.egress_flow_snapshots ORDER BY 1
                        """).query(String.class).list();
    }

    @Test
    void writesTheUnionOfAllQueriesAndIsIdempotent() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);

        assertThat(runner.run(collector(busyWindow()))).isTrue();

        List<String> rows = flows();
        assertThat(rows).containsExactly(
                "mba1|apps|furchert-ch|furchert-ch|10.43.0.10:8080|10.42.1.7:8080|10.42.1.7|8080|pod|-|300|0|0|2|true",
                "mba1|apps|litellm|litellm|203.0.113.50:443||203.0.113.50|443|external|-|0|0|0|1|true",
                "mba1|apps|litellm|litellm|34.117.59.81:443|34.117.59.81:443|34.117.59.81|443|external|a.example.com|500|0|1|0|true",
                "mba1|apps|litellm|litellm|api.anthropic.com:443||-|443|external|api.anthropic.com|1200|9000|3|0|true",
                "raspi5|-|-|k3s.service|140.82.121.4:443|140.82.121.4:443|140.82.121.4|443|external|github.com|50|0|0|0|true",
                "raspi5|apps|n8n|n8n|10.43.0.20:5678|10.42.1.8:5678|10.42.1.8|5678|pod|-|0|0|0|0|true",
                "raspi5|apps|n8n|n8n|10.43.0.20:5678|10.42.2.9:5678|10.42.2.9|5678|pod|-|0|0|0|0|true",
                "raspi5|apps|n8n|n8n|10.43.0.20:5678||10.43.0.20|5678|service|-|0|0|0|4|true",
                "raspi5|flux-system|source-controller|manager|140.82.121.3:443|140.82.121.3:443|140.82.121.3|443|external|-|0|0|0|0|true");
        Map<String, Object> meta = jdbc.sql("""
                        SELECT min(window_start) AS ws, max(window_end) AS we, min(source) AS source,
                               count(DISTINCT workload_key) AS keys
                        FROM netmon.egress_flow_snapshots
                        """).query().singleRow();
        assertThat(meta.get("ws")).isEqualTo(ts("2026-09-24T09:00:00Z"));
        assertThat(meta.get("we")).isEqualTo(ts("2026-09-24T10:00:00Z"));
        assertThat(meta.get("source")).isEqualTo("prometheus-coroot");
        assertThat(jdbc.sql("SELECT workload_key FROM netmon.egress_flow_snapshots WHERE node = 'raspi5' AND container = 'k3s.service'")
                .query(String.class).single()).isEqualTo(K3S);
        assertThat(mark()).isEqualTo(T);
        assertThat(state.find(EgressCollector.NAME).orElseThrow().lastErrorCode()).isNull();

        // Re-collect the same window: replaced, not duplicated, and is_new does not see its own previous copy.
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        assertThat(runner.run(collector(busyWindow()))).isTrue();
        assertThat(flows()).isEqualTo(rows);
    }

    @Test
    void onlyPublicExternalDestinationIpsAreEnrichedAsEgress() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        collector(busyWindow()).collect();

        assertThat(jdbc.sql("""
                        SELECT host(ip) || '|' || array_to_string(seen_in, ',') || '|' || source FROM netmon.ip_enrichment
                        ORDER BY ip
                        """).query(String.class).list())
                .containsExactly("34.117.59.81|egress|prometheus-coroot", "140.82.121.3|egress|prometheus-coroot",
                        "140.82.121.4|egress|prometheus-coroot", "203.0.113.50|egress|prometheus-coroot");
    }

    @Test
    void isNewUsesTheRolloutStableIdentityAndA30DayLookback() {
        // Seen in an earlier window by a previous pod of the same Deployment.
        insert("2026-09-24T07:00:00Z", "/k8s/apps/litellm-6f7d8c9b4-p2q3r/litellm", "apps/litellm/litellm",
                "34.117.59.81:443", "34.117.59.81", null);
        // Same name destination, 31 days ago: outside the lookback.
        insert("2026-08-24T08:00:00Z", LITELLM, "apps/litellm/litellm", "api.anthropic.com:443", null, "api.anthropic.com");
        // Same destination, but another namespace's workload of the same name.
        insert("2026-09-24T08:00:00Z", "/k8s/other/k3s-6f7d8c9b4-p2q3r/k3s", "other/k3s/k3s", "140.82.121.4:443",
                "140.82.121.4", null);
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);

        collector(busyWindow()).collect();

        assertThat(jdbc.sql("""
                        SELECT destination || '|' || is_new FROM netmon.egress_flow_snapshots
                        WHERE window_start = '2026-09-24T09:00:00Z' AND container_id IN (:litellm, :k3s)
                        ORDER BY 1
                        """).param("litellm", LITELLM).param("k3s", K3S).query(String.class).list())
                .containsExactly("140.82.121.4:443|true", "203.0.113.50:443|true", "34.117.59.81:443|false",
                        "api.anthropic.com:443|true");
    }

    @Test
    void theNextWindowSeesTheFlowsOfThePreviousOneAsKnown() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END.minusSeconds(3600));
        Instant previous = PREVIOUS_END;
        FakePrometheus prometheus = busyWindow()
                .on(EgressQueries.PRESENCE, previous, sample(1, flow("mba1", LITELLM, "api.anthropic.com:443", null)));

        collector(prometheus).collect();

        assertThat(mark()).isEqualTo(T);
        assertThat(jdbc.sql("""
                        SELECT to_char(window_start AT TIME ZONE 'UTC', 'HH24') || '|' || is_new
                        FROM netmon.egress_flow_snapshots WHERE destination = 'api.anthropic.com:443' ORDER BY 1
                        """).query(String.class).list())
                .containsExactly("08|true", "09|false");
    }

    @Test
    void rowCapKeepsTheLargestFlowsAndWarnsTruncated() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);

        assertThat(runner.run(collector(busyWindow(), NOW, 12, 2))).isTrue();

        assertThat(jdbc.sql("SELECT destination FROM netmon.egress_flow_snapshots ORDER BY bytes_sent DESC")
                .query(String.class).list())
                .containsExactly("api.anthropic.com:443", "34.117.59.81:443");
        CollectorState row = state.find(EgressCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isEqualTo("truncated");
        assertThat(row.lastError()).isEqualTo("CollectorWarning: 1" + EgressCollector.TRUNCATED);
        assertThat(row.consecutiveFailures()).isZero();
        assertThat(row.lastWindowEnd()).isEqualTo(T);
    }

    @Test
    void bytesSentAtItsTopkBoundWarnsTruncated() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        var sent = new ch.furchert.homelab.data.netmon.prometheus.PrometheusSample[EgressQueries.BYTES_SENT_TOP];
        for (int i = 0; i < sent.length; i++) {
            sent[i] = sample(1000 - i, flow("mba1", LITELLM, "34.117." + (i / 250) + "." + (i % 250) + ":443", ""));
        }
        FakePrometheus prometheus = new FakePrometheus()
                .on(EgressQueries.BYTES_SENT, T, sent)
                .on(EgressQueries.AGENTS, T, sample(1, "node", "mba1"));

        assertThat(runner.run(collector(prometheus))).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_snapshots").query(Long.class).single()).isEqualTo(500L);
        CollectorState row = state.find(EgressCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isEqualTo("truncated");
        assertThat(row.consecutiveFailures()).isZero();
    }

    @Test
    void withoutAnyAgentTheRunSucceedsWithAnUpstreamWarningAndAdvances() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);

        assertThat(runner.run(collector(new FakePrometheus()))).isTrue();

        CollectorState row = state.find(EgressCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isEqualTo("upstream");
        assertThat(row.lastError()).isEqualTo("CollectorWarning: " + EgressCollector.NO_AGENTS);
        assertThat(row.consecutiveFailures()).isZero();
        assertThat(row.lastSuccessAt()).isNotNull();
        assertThat(row.lastWindowEnd()).isEqualTo(T);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.egress_flow_snapshots").query(Long.class).single()).isZero();
    }

    @Test
    void catchUpThroughWindowsBeforeTheRolloutDoesNotWarn() {
        FakePrometheus prometheus = new FakePrometheus().on(EgressQueries.AGENTS, T, sample(1, "node", "raspi5"));

        assertThat(runner.run(collector(prometheus, NOW, 4, 2000))).isTrue();

        CollectorState row = state.find(EgressCollector.NAME).orElseThrow();
        assertThat(row.lastErrorCode()).isNull();
        assertThat(row.lastWindowEnd()).isEqualTo(T.minusSeconds(48 * 3600).plusSeconds(4 * 3600));
    }

    @Test
    void prometheusFailureKeepsTheMark() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus();
        prometheus.failure = new CollectorException(ErrorCode.UPSTREAM, "Prometheus unreachable");

        assertThat(runner.run(collector(prometheus))).isFalse();

        CollectorState row = state.find(EgressCollector.NAME).orElseThrow();
        assertThat(row.lastWindowEnd()).isEqualTo(PREVIOUS_END);
        assertThat(row.consecutiveFailures()).isEqualTo(1);
        assertThat(row.lastErrorCode()).isEqualTo("upstream");
    }

    @Test
    void gapBeyond48HoursIsSkipped() {
        state.updateWindowEnd(EgressCollector.NAME, Instant.parse("2026-09-20T00:00:00Z"));
        FakePrometheus prometheus = new FakePrometheus();

        runner.run(collector(prometheus, NOW, 2, 2000));

        assertThat(mark()).isEqualTo(Instant.parse("2026-09-22T12:00:00Z"));
        assertThat(prometheus.calls).noneMatch(c -> c.startsWith(Instant.parse("2026-09-21T00:00:00Z").getEpochSecond() + " "));
    }

    @Test
    void nothingToDoBeforeTheEvaluationDelayHasPassed() {
        state.updateWindowEnd(EgressCollector.NAME, T);
        FakePrometheus prometheus = new FakePrometheus();

        collector(prometheus, Instant.parse("2026-09-24T11:04:59Z"), 12, 2000).collect();

        assertThat(prometheus.calls).isEmpty();
        assertThat(mark()).isEqualTo(T);
    }

    @Test
    void seriesWithUnexpectedLabelsOrValuesAreDroppedNotFatal() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        FakePrometheus prometheus = new FakePrometheus()
                .on(EgressQueries.BYTES_SENT, T,
                        sample(1, flow("mba1", LITELLM, "34.117.59.81:443", "34.117.59.81:443")),
                        sample(1, flow("mba1", "not-a-container-id", "34.117.59.81:443", "34.117.59.81:443")),
                        sample(1, flow("mba1", LITELLM, "34.117.59.81", "34.117.59.81:443")),
                        sample(1, flow("mba1", LITELLM, "34.117.59.81:443", "evil.example:443")),
                        sample(Double.NaN, flow("mba1", LITELLM, "34.117.59.82:443", "34.117.59.82:443")),
                        sample(-1, flow("mba1", LITELLM, "34.117.59.83:443", "34.117.59.83:443")),
                        sample(1, "container_id", LITELLM, "destination", "34.117.59.84:443"))
                .on(EgressQueries.FQDN, T,
                        sample(1, "ip", "not-an-ip", "fqdn", "x.example.com"),
                        sample(1, "ip", "34.117.59.81", "fqdn", "bad name"));

        assertThat(runner.run(collector(prometheus))).isTrue();

        assertThat(flows()).containsExactly(
                "mba1|apps|litellm|litellm|34.117.59.81:443|34.117.59.81:443|34.117.59.81|443|external|-|1|0|0|0|true");
    }

    @Test
    void endToEndOverHttpUsesTheContractQueriesAndTime() {
        state.updateWindowEnd(EgressCollector.NAME, PREVIOUS_END);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        String url = "http://prometheus.test:9090/api/v1/query";
        String litellm = "{\"node\":\"mba1\",\"container_id\":\"" + LITELLM
                + "\",\"destination\":\"34.117.59.81:443\",\"actual_destination\":\"34.117.59.81:443\"}";
        expect(server, url, EgressQueries.BYTES_SENT, litellm, "1024");
        expect(server, url, EgressQueries.BYTES_RECEIVED, litellm, "4096");
        expect(server, url, EgressQueries.CONNECTS, litellm, "2");
        expect(server, url, EgressQueries.FAILED_CONNECTS, null, null);
        expect(server, url, EgressQueries.FQDN, "{\"ip\":\"34.117.59.81\",\"fqdn\":\"api.example.com\"}", "1");
        expect(server, url, EgressQueries.PRESENCE, litellm, "1");
        expect(server, url, EgressQueries.AGENTS, "{\"node\":\"mba1\"}", "12");
        PrometheusClient client = new PrometheusClient(builder.build(), new PrometheusProperties("http://prometheus.test:9090"),
                JsonMapper.builder().build());

        assertThat(runner.run(collector(client))).isTrue();

        server.verify();
        assertThat(flows()).containsExactly(
                "mba1|apps|litellm|litellm|34.117.59.81:443|34.117.59.81:443|34.117.59.81|443|external|api.example.com|1024|4096|2|0|true");
        assertThat(state.find(EgressCollector.NAME).orElseThrow().lastErrorCode()).isNull();
    }

    private void insert(String windowStart, String containerId, String workloadKey, String destination, String ip,
                        String fqdn) {
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, workload_key,
                            destination, actual_destination, destination_ip, destination_port, destination_scope, fqdn,
                            bytes_sent, bytes_received, connects, failed_connects, is_new, source)
                        VALUES (CAST(:ws AS timestamptz), CAST(:ws AS timestamptz) + interval '1 hour', 'mba1', :cid, :key,
                                :dst, '', CAST(:ip AS inet), 443, 'external', :fqdn, 1, 1, 1, 0, true, 'prometheus-coroot')
                        """)
                .param("ws", windowStart).param("cid", containerId).param("key", workloadKey).param("dst", destination)
                .param("ip", ip).param("fqdn", fqdn)
                .update();
    }

    private static void expect(MockRestServiceServer server, String url, String query, String metric, String value) {
        String result = metric == null ? "" : "{\"metric\":" + metric + ",\"value\":[" + T.getEpochSecond() + ",\"" + value + "\"]}";
        server.expect(requestTo(url))
                .andExpect(content().formData(MultiValueMap.fromSingleValue(Map.of(
                        "query", query, "time", Long.toString(T.getEpochSecond())))))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + result + "]}}",
                        MediaType.APPLICATION_JSON));
    }

    private Instant mark() {
        return state.find(EgressCollector.NAME).map(CollectorState::lastWindowEnd).orElse(null);
    }

    private static Object ts(String instant) {
        return java.sql.Timestamp.from(Instant.parse(instant));
    }
}
