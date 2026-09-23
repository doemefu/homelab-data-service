package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.CloudflareProperties;
import ch.furchert.homelab.data.netmon.cloudflare.CloudflareGraphqlClient;
import ch.furchert.homelab.data.netmon.cloudflare.InboundRequestRepository;
import ch.furchert.homelab.data.netmon.cloudflare.InboundRequestsCollector;
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
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.group;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.groupsResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** cloudflare-requests (docs/060 §4.1, §4.2 query A) against Postgres and a stubbed Cloudflare. */
class InboundRequestsCollectorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    InboundRequestRepository repository;
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

    /** A collector at a fixed time whose Cloudflare stub gets the expectations from {@code stub}. */
    private InboundRequestsCollector collector(Instant now, int pageSize, int maxQueries,
                                               Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        CloudflareProperties props = new CloudflareProperties(URL, "token", "zone", pageSize, 1000, 20, maxQueries);
        return new InboundRequestsCollector(new CloudflareGraphqlClient(builder.build(), props, json), repository,
                enrichment, state, props, runner, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void expectWindow(MockRestServiceServer server, String since, String until, String body) {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.variables.since").value(since))
                .andExpect(jsonPath("$.variables.until").value(until))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static final String HOUR9 = groupsResponse(
            group("203.0.113.7", "furchert.ch", "GET", "/de", 200, 120, 1),
            group("198.51.100.9", "auth.furchert.ch", "POST", "/login", 401, 7, 1));
    private static final String HOUR10 = groupsResponse(
            group("203.0.113.7", "furchert.ch", "GET", "/en", 200, 3, 1));

    @Test
    void writesFinalAndOpenHoursAndIsIdempotent() {
        Instant now = Instant.parse("2026-09-23T10:30:00Z");
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-23T09:00:00Z"));

        collector(now, 5000, 60, s -> {
            expectWindow(s, "2026-09-23T09:00:00Z", "2026-09-23T10:00:00Z", HOUR9);
            expectWindow(s, "2026-09-23T10:00:00Z", "2026-09-23T10:28:00Z", HOUR10);
        }).collect();
        List<Map<String, Object>> first = rows();

        assertThat(first).hasSize(3);
        assertThat(first).filteredOn(r -> r.get("window_start").equals(ts("2026-09-23T09:00:00Z")))
                .allSatisfy(r -> assertThat(r.get("is_final")).isEqualTo(true))
                .hasSize(2);
        assertThat(first).filteredOn(r -> r.get("window_start").equals(ts("2026-09-23T10:00:00Z")))
                .singleElement().satisfies(r -> assertThat(r.get("is_final")).isEqualTo(false));
        assertThat(highWaterMark()).isEqualTo(Instant.parse("2026-09-23T10:00:00Z"));

        // Re-collect both hours from scratch: the windows are replaced, not duplicated.
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-23T09:00:00Z"));
        collector(now, 5000, 60, s -> {
            expectWindow(s, "2026-09-23T09:00:00Z", "2026-09-23T10:00:00Z", HOUR9);
            expectWindow(s, "2026-09-23T10:00:00Z", "2026-09-23T10:28:00Z", HOUR10);
        }).collect();
        assertThat(rows()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id", "ingested_at")
                .isEqualTo(first);
    }

    @Test
    void openHourBecomesFinal15MinutesAfterItsEnd() {
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-23T10:00:00Z"));
        collector(Instant.parse("2026-09-23T11:16:00Z"), 5000, 60, s -> {
            expectWindow(s, "2026-09-23T10:00:00Z", "2026-09-23T11:00:00Z", HOUR10);
            expectWindow(s, "2026-09-23T11:00:00Z", "2026-09-23T11:14:00Z", groupsResponse());
        }).collect();

        assertThat(jdbc.sql("SELECT is_final FROM netmon.inbound_request_groups").query(Boolean.class).list())
                .containsExactly(true);
        assertThat(highWaterMark()).isEqualTo(Instant.parse("2026-09-23T11:00:00Z"));
    }

    @Test
    void enrichesPublicSourceIps() {
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-23T09:00:00Z"));
        collector(Instant.parse("2026-09-23T10:30:00Z"), 5000, 60, s -> {
            expectWindow(s, "2026-09-23T09:00:00Z", "2026-09-23T10:00:00Z", HOUR9);
            expectWindow(s, "2026-09-23T10:00:00Z", "2026-09-23T10:28:00Z", HOUR10);
        }).collect();

        Map<String, Object> row = jdbc.sql("""
                        SELECT first_seen, last_seen, array_to_string(seen_in, ',') AS seen_in, country, asn, asn_org
                        FROM netmon.ip_enrichment WHERE ip = '203.0.113.7'
                        """).query().singleRow();
        assertThat(row.get("first_seen")).isEqualTo(ts("2026-09-23T09:00:00Z"));
        assertThat(row.get("last_seen")).isEqualTo(ts("2026-09-23T10:28:00Z"));
        assertThat(row.get("seen_in")).isEqualTo("inbound");
        assertThat(row.get("country")).isEqualTo("DE");
        assertThat(row.get("asn")).isEqualTo(3320);
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.ip_enrichment").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void gapBeyond24hIsSkippedAndCatchUpIsThrottled() {
        Instant now = Instant.parse("2026-09-23T10:30:00Z");
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-20T00:00:00Z"));

        // The minimum budget of 13 queries covers 13 unsliced hours from the 24 h floor.
        collector(now, 5000, 13, s -> {
            for (int h = 10; h < 23; h++) {
                expectWindow(s, "2026-09-22T%02d:00:00Z".formatted(h), "2026-09-22T%02d:00:00Z".formatted(h + 1),
                        h == 10 ? HOUR9 : groupsResponse());
            }
        }).collect();

        assertThat(highWaterMark()).isEqualTo(Instant.parse("2026-09-22T23:00:00Z"));
    }

    @Test
    void fullPageIsSlicedSummedAndTruncationIsReportedAsWarning() {
        String longA = "/" + "x".repeat(1100) + "A";
        String longB = "/" + "x".repeat(1100) + "B";
        state.updateWindowEnd(InboundRequestsCollector.NAME, Instant.parse("2026-09-23T09:00:00Z"));
        InboundRequestsCollector collector = collector(Instant.parse("2026-09-23T10:16:00Z"), 2, 60, s -> {
            expectWindow(s, "2026-09-23T09:00:00Z", "2026-09-23T10:00:00Z", groupsResponse(
                    group("203.0.113.7", "furchert.ch", "GET", "/", 200, 1, 1),
                    group("203.0.113.8", "furchert.ch", "GET", "/", 200, 1, 1)));
            for (int i = 0; i < 12; i++) {
                String since = "2026-09-23T09:%02d:00Z".formatted(i * 5);
                String until = i == 11 ? "2026-09-23T10:00:00Z" : "2026-09-23T09:%02d:00Z".formatted(i * 5 + 5);
                String body = switch (i) {
                    case 0 -> groupsResponse(group("203.0.113.7", "furchert.ch", "GET", "/", 200, 3, 1));
                    case 1 -> groupsResponse(group("203.0.113.7", "furchert.ch", "GET", "/", 200, 4, 10));
                    // A full slice (2 rows = limit) whose two paths collide after truncation to 1024 chars.
                    case 2 -> groupsResponse(group("203.0.113.9", "furchert.ch", "GET", longA, 404, 1, 1),
                            group("203.0.113.9", "furchert.ch", "GET", longB, 404, 2, 1));
                    default -> groupsResponse();
                };
                expectWindow(s, since, until, body);
            }
            expectWindow(s, "2026-09-23T10:00:00Z", "2026-09-23T10:14:00Z", groupsResponse());
        });

        assertThat(runner.run(collector)).isTrue();

        assertThat(jdbc.sql("""
                        SELECT request_count FROM netmon.inbound_request_groups
                        WHERE client_ip = '203.0.113.7' AND path = '/'
                        """).query(Long.class).single()).isEqualTo(7L);
        assertThat(jdbc.sql("SELECT sampled FROM netmon.inbound_request_groups WHERE client_ip = '203.0.113.7'")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT request_count FROM netmon.inbound_request_groups WHERE client_ip = '203.0.113.9'
                        """).query(Long.class).single()).isEqualTo(3L);
        Map<String, Object> st = jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'cloudflare-requests'")
                .query().singleRow();
        assertThat(st.get("last_error_code")).isEqualTo("truncated");
        assertThat(st.get("consecutive_failures")).isEqualTo(0);
        assertThat(st.get("last_success_at")).isNotNull();
        assertThat((String) st.get("last_error")).doesNotContain("203.0.113");
    }

    @Test
    void missingTokenFailsWithCredentials() {
        CloudflareProperties props = new CloudflareProperties(URL, "", "", 5000, 1000, 20, 60);
        InboundRequestsCollector collector = new InboundRequestsCollector(
                new CloudflareGraphqlClient(RestClient.builder().build(), props, json), repository, enrichment, state,
                props, runner, Clock.fixed(Instant.parse("2026-09-23T10:30:00Z"), ZoneOffset.UTC));

        assertThat(runner.run(collector)).isFalse();

        Map<String, Object> st = jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'cloudflare-requests'")
                .query().singleRow();
        assertThat(st.get("last_error_code")).isEqualTo("credentials");
        assertThat(st.get("consecutive_failures")).isEqualTo(1);
        assertThat(st.get("last_window_end")).isNull();
    }

    private List<Map<String, Object>> rows() {
        return jdbc.sql("SELECT * FROM netmon.inbound_request_groups ORDER BY window_start, client_ip, path")
                .query().listOfRows();
    }

    private Instant highWaterMark() {
        return state.find(InboundRequestsCollector.NAME).orElseThrow().lastWindowEnd();
    }

    private static Object ts(String instant) {
        return java.sql.Timestamp.from(Instant.parse(instant));
    }

}
