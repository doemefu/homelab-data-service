package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.CloudflareProperties;
import ch.furchert.homelab.data.netmon.cloudflare.CloudflareGraphqlClient;
import ch.furchert.homelab.data.netmon.cloudflare.FirewallEventRepository;
import ch.furchert.homelab.data.netmon.cloudflare.FirewallEventsCollector;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.URL;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.event;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.eventsResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** cloudflare-firewall (docs/060 §4.1, §4.2 query B): keyset paging, overlap and idempotency. */
class FirewallEventsCollectorIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:30:00Z");
    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    FirewallEventRepository repository;
    @Autowired
    IpEnrichmentService enrichment;
    @Autowired
    CollectorStateRepository state;
    @Autowired
    CollectorRunner runner;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    private FirewallEventsCollector collector(int pageSize, int maxPages, Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        CloudflareProperties props = new CloudflareProperties(URL, "token", "zone", 5000, pageSize, maxPages, 60);
        return new FirewallEventsCollector(new CloudflareGraphqlClient(builder.build(), props, json), repository,
                enrichment, state, props, runner, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void expectPage(MockRestServiceServer server, String op, Instant since, List<String> events) {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query", containsString(op + ": $since")))
                .andExpect(jsonPath("$.variables.since").value(since.toString()))
                .andExpect(jsonPath("$.variables.until").value("2026-09-23T10:28:00Z"))
                .andRespond(withSuccess(eventsResponse(events), MediaType.APPLICATION_JSON));
    }

    private static String ev(int second, String ray, String action) {
        return event(T0.plusSeconds(second), ray, "198.51.100.9", action, "rule-1");
    }

    @Test
    void firstRunPagesFrom24hBackAndReRunCreatesNoDuplicates() {
        List<String> page1 = List.of(ev(0, "r0", "block"), ev(1, "r1", "block"), ev(2, "r2", "managed_challenge"));
        List<String> page2 = List.of(ev(2, "r2", "managed_challenge"), ev(3, "r3", "block"));
        collector(3, 20, s -> {
            expectPage(s, "datetime_geq", Instant.parse("2026-09-22T10:28:00Z"), page1);
            expectPage(s, "datetime_geq", T0.plusSeconds(2), page2);
        }).collect();

        assertThat(count()).isEqualTo(4L);
        assertThat(highWaterMark()).isEqualTo(Instant.parse("2026-09-23T10:28:00Z"));

        // Next run re-reads the 10-minute overlap; the natural key absorbs the duplicates.
        collector(3, 20, s -> expectPage(s, "datetime_geq", Instant.parse("2026-09-23T10:18:00Z"), page2)).collect();
        assertThat(count()).isEqualTo(4L);
        assertThat(jdbc.sql("SELECT array_to_string(seen_in, ',') FROM netmon.ip_enrichment WHERE ip = '198.51.100.9'")
                .query(String.class).single()).isEqualTo("firewall");
    }

    @Test
    void fullPageWithOneTimestampContinuesWithDatetimeGtAndWarnsTruncated() {
        FirewallEventsCollector collector = collector(2, 20, s -> {
            expectPage(s, "datetime_geq", Instant.parse("2026-09-22T10:28:00Z"),
                    List.of(ev(5, "a", "block"), ev(5, "b", "block")));
            expectPage(s, "datetime_gt", T0.plusSeconds(5), List.of(ev(6, "c", "block")));
        });

        assertThat(runner.run(collector)).isTrue();

        assertThat(count()).isEqualTo(3L);
        Map<String, Object> st = jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'cloudflare-firewall'")
                .query().singleRow();
        assertThat(st.get("last_error_code")).isEqualTo("truncated");
        assertThat(st.get("consecutive_failures")).isEqualTo(0);
        assertThat(highWaterMark()).isEqualTo(Instant.parse("2026-09-23T10:28:00Z"));
    }

    @Test
    void pageCapSetsHighWaterMarkToLastFetchedEvent() {
        collector(2, 2, s -> {
            expectPage(s, "datetime_geq", Instant.parse("2026-09-22T10:28:00Z"), List.of(ev(0, "a", "block"), ev(1, "b", "block")));
            expectPage(s, "datetime_geq", T0.plusSeconds(1), List.of(ev(1, "b", "block"), ev(2, "c", "block")));
        }).collect();

        assertThat(count()).isEqualTo(3L);
        assertThat(highWaterMark()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    void oneRequestCanTriggerSeveralEvents() {
        collector(10, 20, s -> expectPage(s, "datetime_geq", Instant.parse("2026-09-22T10:28:00Z"), List.of(
                event(T0, "same-ray", "198.51.100.9", "log", "rule-1"),
                event(T0, "same-ray", "198.51.100.9", "block", "rule-2"),
                event(T0, "same-ray", "198.51.100.9", "block", null)))).collect();

        assertThat(count()).isEqualTo(3L);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM netmon.firewall_events").query(Long.class).single();
    }

    private Instant highWaterMark() {
        return state.find(FirewallEventsCollector.NAME).orElseThrow().lastWindowEnd();
    }
}
