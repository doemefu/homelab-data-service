package ch.furchert.homelab.data.netmon.login;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.AuthServiceProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

import static ch.furchert.homelab.data.netmon.login.LoginFixtures.BASE_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.EVENTS_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.HMAC_A;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.HMAC_B;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.TOKEN_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.event;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.page;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.tokenResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** login-events (docs/060 §4.1, §7.6): cursor paging, idempotent upsert, enrichment and status semantics. */
class LoginEventsCollectorIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T14:00:15Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    LoginEventRepository repository;
    @Autowired
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
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

    private LoginEventsCollector collector(String secret, int pageSize, int maxPages, Consumer<MockRestServiceServer> stub) {
        return collector(secret, pageSize, maxPages, enrichment, stub);
    }

    private LoginEventsCollector collector(String secret, int pageSize, int maxPages, IpEnrichmentService enrichment,
                                           Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        AuthServiceProperties props = new AuthServiceProperties(TOKEN_URL, BASE_URL, "data-service", secret, pageSize,
                maxPages);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AuthServiceClient client = new AuthServiceClient(builder.build(), props, JsonMapper.builder().build(), clock);
        return new LoginEventsCollector(client, repository, enrichment, state, props, runner, clock);
    }

    private static void token(MockRestServiceServer server) {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(tokenResponse("tok", 900), MediaType.APPLICATION_JSON));
    }

    private static void expectPage(MockRestServiceServer server, long after, int limit, String body) {
        server.expect(requestTo(EVENTS_URL + "?after=" + after + "&limit=" + limit))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String failure(long id, String ip) {
        return event(id, "2026-09-24T13:5" + (id % 10) + ":00Z", "failure", ip, "cf-connecting-ip", HMAC_B, null,
                "Mozilla/5.0");
    }

    @Test
    void firstRunPagesFromZeroWhileHasMoreAndPersistsTheCursor() {
        LoginEventsCollector collector = collector("secret", 2, 10, s -> {
            token(s);
            expectPage(s, 0, 2, page(List.of(failure(1, "203.0.113.7"), failure(2, "10.42.1.5")), 2, true));
            expectPage(s, 2, 2, page(List.of(event(5, "2026-09-24T13:59:00Z", "success", "203.0.113.7",
                    "cf-connecting-ip", HMAC_A, "dominic", null)), 5, false));
        });

        assertThat(runner.run(collector)).isTrue();

        assertThat(count()).isEqualTo(3L);
        Map<String, Object> st = stateRow();
        assertThat(st.get("cursor")).isEqualTo("5");
        assertThat(st.get("consecutive_failures")).isEqualTo(0);
        assertThat(st.get("last_error_code")).isNull();
        assertThat(state.find("login-events").orElseThrow().lastWindowEnd()).isEqualTo(Instant.parse("2026-09-24T14:00:05Z"));
        Map<String, Object> success = jdbc.sql("""
                        SELECT outcome, host(client_ip) AS ip, ip_source, username_hmac, subject, user_agent,
                               source_service, source
                        FROM netmon.login_events WHERE event_id = CAST(:id AS uuid)
                        """)
                .param("id", LoginFixtures.eventId(5)).query().singleRow();
        assertThat(success).containsEntry("outcome", "success").containsEntry("ip", "203.0.113.7")
                .containsEntry("ip_source", "cf-connecting-ip").containsEntry("username_hmac", HMAC_A)
                .containsEntry("subject", "dominic").containsEntry("source_service", "auth-service")
                .containsEntry("source", "auth-service");
        assertThat(success.get("user_agent")).isNull();
        // Only the public IP is enriched (docs/060 §4.3), with the login data set.
        assertThat(jdbc.sql("SELECT host(ip) || ':' || array_to_string(seen_in, ',') FROM netmon.ip_enrichment")
                .query(String.class).list()).containsExactly("203.0.113.7:login");
    }

    @Test
    void nextRunContinuesAfterTheCursorAndReplaysAreIdempotent() {
        collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 0, 500, page(List.of(failure(1, "203.0.113.7"), failure(2, "203.0.113.8")), 2, false));
        }).collect();
        // The cursor is reset by hand: the same events come back and are absorbed by event_id.
        state.updateCursor(LoginEventsCollector.NAME, "1");
        collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 1, 500, page(List.of(failure(2, "203.0.113.8"), failure(3, "203.0.113.9")), 3, false));
        }).collect();

        assertThat(count()).isEqualTo(3L);
        assertThat(stateRow().get("cursor")).isEqualTo("3");
    }

    @Test
    void pageCapStopsTheRunWithoutMovingTheHighWaterMark() {
        LoginEventsCollector collector = collector("secret", 1, 2, s -> {
            token(s);
            expectPage(s, 0, 1, page(List.of(failure(1, "203.0.113.7")), 1, true));
            expectPage(s, 1, 1, page(List.of(failure(2, "203.0.113.7")), 2, true));
        });

        assertThat(runner.run(collector)).isTrue();

        assertThat(count()).isEqualTo(2L);
        Map<String, Object> st = stateRow();
        assertThat(st.get("cursor")).isEqualTo("2");
        assertThat(st.get("last_window_end")).isNull();
        assertThat(st.get("last_error_code")).isNull();
    }

    @Test
    void skippedRowsAdvanceTheCursorAndEndThePartialWarning() {
        LoginEventsCollector collector = collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 0, 500, page(List.of(
                    event(1, "2026-09-24T13:00:00Z", "denied", "203.0.113.7", "remote-addr", HMAC_A, null, null),
                    failure(2, null)), 2, false));
        });

        assertThat(runner.run(collector)).isTrue();

        assertThat(count()).isEqualTo(1L);
        Map<String, Object> st = stateRow();
        assertThat(st.get("cursor")).isEqualTo("2");
        assertThat(st.get("last_error_code")).isEqualTo("partial");
        assertThat(st.get("consecutive_failures")).isEqualTo(0);
        assertThat((String) st.get("last_error")).contains("skipped 1 ").doesNotContain("203.0.113.7").doesNotContain(HMAC_A);
        assertThat(st.get("last_window_end")).isNotNull();
    }

    @Test
    void cursorStaysWhenEnrichmentFails() {
        IpEnrichmentService failing = new IpEnrichmentService(named, jdbc) {
            @Override
            public int record(String dataset, String source, java.util.Collection<ch.furchert.homelab.data.netmon.enrichment.Sighting> sightings) {
                throw new IllegalStateException("enrichment down");
            }
        };
        state.updateCursor(LoginEventsCollector.NAME, "4");
        LoginEventsCollector collector = collector("secret", 500, 10, failing, s -> {
            token(s);
            expectPage(s, 4, 500, page(List.of(failure(5, "203.0.113.7")), 5, false));
        });

        assertThat(runner.run(collector)).isFalse();

        // The event itself is stored (idempotent), but the next run re-reads the page and enriches it.
        assertThat(stateRow().get("cursor")).isEqualTo("4");
        assertThat(stateRow().get("last_error_code")).isEqualTo("internal");
    }

    @Test
    void credentialFailuresBackOffForThisCollector() {
        LoginEventsCollector collector = collector("secret", 500, 10, s -> {
        });
        assertThat(collector.backsOffAfter(ch.furchert.homelab.data.netmon.collector.ErrorCode.CREDENTIALS)).isTrue();
        assertThat(collector.backsOffAfter(ch.furchert.homelab.data.netmon.collector.ErrorCode.UPSTREAM)).isTrue();
        assertThat(collector.backsOffAfter(ch.furchert.homelab.data.netmon.collector.ErrorCode.INTERNAL)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aRunLongAfterTheLastSuccessLogsTheTtlGap(org.springframework.boot.test.system.CapturedOutput output) {
        state.recordSuccess(LoginEventsCollector.NAME, NOW.minus(java.time.Duration.ofHours(73)));
        collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 0, 500, page(List.of(), 0, false));
        }).collect();

        assertThat(output).contains("[login-events] last success older than the 72 h outbox TTL");
    }

    @Test
    void emptyOutboxKeepsTheCursorAndAdvancesTheHighWaterMark() {
        state.updateCursor(LoginEventsCollector.NAME, "42");
        assertThat(runner.run(collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 42, 500, page(List.of(), 42, false));
        }))).isTrue();

        Map<String, Object> st = stateRow();
        assertThat(st.get("cursor")).isEqualTo("42");
        assertThat(state.find("login-events").orElseThrow().lastWindowEnd()).isEqualTo(Instant.parse("2026-09-24T14:00:05Z"));
    }

    @Test
    void unreadableCursorRestartsFromZero() {
        state.updateCursor(LoginEventsCollector.NAME, "capped");
        collector("secret", 500, 10, s -> {
            token(s);
            expectPage(s, 0, 500, page(List.of(failure(1, "203.0.113.7")), 1, false));
        }).collect();

        assertThat(stateRow().get("cursor")).isEqualTo("1");
    }

    @Test
    void missingSecretFailsWithCredentialsAndNeverCallsOut() {
        LoginEventsCollector collector = collector("", 500, 10, s -> s.expect(never(), requestTo(TOKEN_URL)));

        assertThat(collector.exportsFreshnessGauge()).isFalse();
        assertThat(runner.run(collector)).isFalse();

        Map<String, Object> st = stateRow();
        assertThat(st.get("last_error_code")).isEqualTo("credentials");
        assertThat(st.get("consecutive_failures")).isEqualTo(1);
        assertThat(st.get("last_success_at")).isNull();
    }

    @Test
    void disabledOutboxIsASuccessfulRunWithAnUpstreamWarning() {
        LoginEventsCollector collector = collector("secret", 500, 10, s -> {
            token(s);
            s.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        });

        assertThat(collector.exportsFreshnessGauge()).isTrue();
        assertThat(runner.run(collector)).isTrue();

        Map<String, Object> st = stateRow();
        assertThat(st.get("last_error_code")).isEqualTo("upstream");
        assertThat(st.get("consecutive_failures")).isEqualTo(0);
        assertThat(st.get("last_success_at")).isNotNull();
        assertThat((String) st.get("last_error")).doesNotContain("http");
    }

    @Test
    void cadenceAndNameFollowTheSpec() {
        LoginEventsCollector collector = collector("secret", 500, 10, s -> {
        });
        assertThat(collector.name()).isEqualTo("login-events");
        assertThat(collector.cadence()).hasMinutes(1);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM netmon.login_events").query(Long.class).single();
    }

    private Map<String, Object> stateRow() {
        return jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'login-events'").query().singleRow();
    }
}
