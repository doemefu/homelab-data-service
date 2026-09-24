package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.AbuseIpDbProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.reputation.AbuseIpDbClient;
import ch.furchert.homelab.data.netmon.reputation.ReputationCollector;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** reputation (docs/060 §4.5): candidates, budget cursor, 429 handling, unavailable without a key. */
class ReputationCollectorIntegrationTest extends AbstractIntegrationTest {

    static final String URL = "https://abuseipdb.test/api/v2/check";
    static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollectorStateRepository state;
    @Autowired
    CollectorRunner runner;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        // A: firewall block (priority 2); B: top talker (priority 4); C: blocklisted -> never a candidate;
        // D: checked 2 days ago -> not due.
        jdbc.sql("""
                INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, blocklist_hits, blocklisted,
                                                  abuseipdb_checked_at, source)
                VALUES ('198.51.100.1', now(), now(), ARRAY['firewall'], '[]', false, NULL, 'cloudflare-graphql'),
                       ('198.51.100.2', now(), now(), ARRAY['inbound'], '[]', false, NULL, 'cloudflare-graphql'),
                       ('198.51.100.3', now(), now(), ARRAY['inbound'],
                        '[{"list":"firehol-level1","cidr":"198.51.100.3/32","fetchedAt":"2026-09-23T05:00:00Z"}]', true,
                        NULL, 'cloudflare-graphql'),
                       ('198.51.100.4', now(), now(), ARRAY['inbound'], '[]', false,
                        CAST(? AS timestamptz) - interval '2 days', 'cloudflare-graphql')
                """).param(java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO netmon.firewall_events (occurred_at, ray_name, client_ip, action, security_source, source)
                VALUES (CAST(? AS timestamptz) - interval '1 hour', 'r1', '198.51.100.1', 'block', 'firewallManaged', 'cloudflare-graphql'),
                       (CAST(? AS timestamptz) - interval '1 hour', 'r2', '198.51.100.2', 'log', 'firewallManaged', 'cloudflare-graphql')
                """).param(java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .param(java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
        for (String ip : new String[]{"198.51.100.2", "198.51.100.3", "198.51.100.4"}) {
            jdbc.sql("""
                    INSERT INTO netmon.inbound_request_groups (window_start, window_end, is_final, client_ip, host, method,
                        path, status, request_count, sample_interval, source)
                    VALUES ('2026-09-23T08:00:00Z', '2026-09-23T09:00:00Z', true, CAST(? AS inet), 'furchert.ch', 'GET',
                            '/', 200, 10, 1, 'cloudflare-graphql')
                    """).param(ip).update();
        }
    }

    private ReputationCollector collector(String key, int perRun, int daily, Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        AbuseIpDbProperties props = new AbuseIpDbProperties(URL, key, daily, perRun);
        return new ReputationCollector(new AbuseIpDbClient(builder.build(), props, json), jdbc, state, props, runner,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String body(int score, int reports) {
        return "{\"data\":{\"ipAddress\":\"x\",\"abuseConfidenceScore\":" + score + ",\"totalReports\":" + reports
                + ",\"countryCode\":\"US\"}}";
    }

    @Test
    void checksCandidatesInPriorityOrderAndCountsTheBudget() {
        collector("key-1", 10, 200, s -> {
            s.expect(requestTo(URL + "?ipAddress=198.51.100.1&maxAgeInDays=90")).andExpect(header("Key", "key-1"))
                    .andRespond(withSuccess(body(87, 412), MediaType.APPLICATION_JSON));
            s.expect(requestTo(URL + "?ipAddress=198.51.100.2&maxAgeInDays=90"))
                    .andRespond(withSuccess(body(0, 0), MediaType.APPLICATION_JSON));
        }).collect();

        assertThat(jdbc.sql("""
                        SELECT host(ip) || ':' || abuseipdb_score || ':' || abuseipdb_reports FROM netmon.ip_enrichment
                        WHERE abuseipdb_checked_at = CAST(? AS timestamptz) ORDER BY ip
                        """).param(java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).query(String.class).list())
                .containsExactly("198.51.100.1:87:412", "198.51.100.2:0:0");
        assertThat(state.find(ReputationCollector.NAME).orElseThrow().cursor()).isEqualTo("2026-09-23:2");
    }

    @Test
    void aFailedLoginIn24HoursRanksFirst() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        // E: one locked login 1 h ago (priority 1); a success and a 25 h old failure do not qualify.
        jdbc.sql("""
                INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, blocklist_hits, blocklisted, source)
                VALUES ('198.51.100.5', now(), now(), ARRAY['login'], '[]', false, 'auth-service'),
                       ('198.51.100.6', now(), now(), ARRAY['login'], '[]', false, 'auth-service')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.login_events (event_id, occurred_at, outcome, client_ip, ip_source, username_hmac,
                    subject, source_service, source)
                VALUES (gen_random_uuid(), CAST(? AS timestamptz) - interval '1 hour', 'locked', '198.51.100.5',
                        'cf-connecting-ip', repeat('a', 64), NULL, 'auth-service', 'auth-service'),
                       (gen_random_uuid(), CAST(? AS timestamptz) - interval '1 hour', 'success', '198.51.100.6',
                        'cf-connecting-ip', repeat('a', 64), 'dominic', 'auth-service', 'auth-service'),
                       (gen_random_uuid(), CAST(? AS timestamptz) - interval '25 hours', 'failure', '198.51.100.6',
                        'cf-connecting-ip', repeat('a', 64), NULL, 'auth-service', 'auth-service')
                """).param(now).param(now).param(now).update();

        collector("key-1", 2, 200, s -> {
            s.expect(requestTo(URL + "?ipAddress=198.51.100.5&maxAgeInDays=90"))
                    .andRespond(withSuccess(body(90, 10), MediaType.APPLICATION_JSON));
            s.expect(requestTo(URL + "?ipAddress=198.51.100.1&maxAgeInDays=90"))
                    .andRespond(withSuccess(body(87, 412), MediaType.APPLICATION_JSON));
        }).collect();

        assertThat(jdbc.sql("SELECT abuseipdb_checked_at IS NULL FROM netmon.ip_enrichment WHERE ip = '198.51.100.6'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void aFailureSpecificToOneIpIsCountedAndSkipped() {
        collector("key-1", 10, 200, s -> {
            s.expect(requestTo(URL + "?ipAddress=198.51.100.1&maxAgeInDays=90"))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
            s.expect(requestTo(URL + "?ipAddress=198.51.100.2&maxAgeInDays=90"))
                    .andRespond(withSuccess(body(3, 1), MediaType.APPLICATION_JSON));
        }).collect();

        assertThat(state.find(ReputationCollector.NAME).orElseThrow().cursor()).isEqualTo("2026-09-23:2");
        assertThat(jdbc.sql("""
                        SELECT host(ip) || ':' || coalesce(abuseipdb_score::text, 'null') FROM netmon.ip_enrichment
                        WHERE abuseipdb_checked_at IS NOT NULL AND ip IN ('198.51.100.1', '198.51.100.2') ORDER BY ip
                        """).query(String.class).list())
                .containsExactly("198.51.100.1:null", "198.51.100.2:3");
    }

    @Test
    void respectsTheDailyBudget() {
        state.updateCursor(ReputationCollector.NAME, "2026-09-23:199");
        collector("key-1", 10, 200, s -> s.expect(requestTo(URL + "?ipAddress=198.51.100.1&maxAgeInDays=90"))
                .andRespond(withSuccess(body(5, 1), MediaType.APPLICATION_JSON))).collect();

        assertThat(state.find(ReputationCollector.NAME).orElseThrow().cursor()).isEqualTo("2026-09-23:200");
    }

    @Test
    void rateLimitStopsTheRunAndExhaustsTheDay() {
        ReputationCollector collector = collector("key-1", 10, 200, s -> s.expect(requestTo(URL + "?ipAddress=198.51.100.1&maxAgeInDays=90"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)));

        assertThatThrownBy(collector::collect)
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
        assertThat(state.find(ReputationCollector.NAME).orElseThrow().cursor()).isEqualTo("2026-09-23:200");
    }

    @Test
    void withoutKeyTheCollectorIsUnavailableAndNeverRuns() {
        ReputationCollector collector = collector("", 10, 200, s -> {
        });

        assertThat(collector.available()).isFalse();
        assertThat(runner.run(collector)).isFalse();
        assertThat(state.find(ReputationCollector.NAME)).isEmpty();
    }
}
