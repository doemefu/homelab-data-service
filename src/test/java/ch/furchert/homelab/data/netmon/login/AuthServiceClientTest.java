package ch.furchert.homelab.data.netmon.login;

import ch.furchert.homelab.data.config.AuthServiceProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static ch.furchert.homelab.data.netmon.login.LoginFixtures.BASE_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.EVENTS_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.HMAC_A;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.TOKEN_URL;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.event;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.page;
import static ch.furchert.homelab.data.netmon.login.LoginFixtures.tokenResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Client-credentials token and outbox page fetch (docs/060 §7.6), with the status-API error mapping. */
class AuthServiceClientTest {

    /** Contains characters that RFC 6749 §2.3.1 form-urlencoding changes. */
    private static final String SECRET = "s3cr+t/with=chars";
    private static final Instant NOW = Instant.parse("2026-09-24T14:00:00Z");

    private MockRestServiceServer server;
    private final MutableClock clock = new MutableClock(NOW);

    private AuthServiceClient client(String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new AuthServiceClient(builder.build(),
                new AuthServiceProperties(TOKEN_URL, BASE_URL, "data-service", secret, 500, 10),
                JsonMapper.builder().build(), clock);
    }

    private void expectToken(String accessToken) {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                "data-service:s3cr%2Bt%2Fwith%3Dchars".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(org.springframework.util.MultiValueMap.fromSingleValue(Map.of(
                        "grant_type", "client_credentials", "scope", "login-events:read"))))
                .andRespond(withSuccess(tokenResponse(accessToken, 900), MediaType.APPLICATION_JSON));
    }

    private void expectPage(long after, String accessToken, String body) {
        server.expect(requestTo(EVENTS_URL + "?after=" + after + "&limit=500"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + accessToken))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void fetchesATokenAndParsesAPage() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        expectPage(0, "tok-1", page(List.of(
                event(7, "2026-09-24T13:59:00.123Z", "failure", "203.0.113.7", "cf-connecting-ip", HMAC_A, null, "curl/8"),
                event(8, "2026-09-24T13:59:30Z", "success", null, "remote-addr", HMAC_A, "dominic", null)), 8, true));

        LoginEventPage result = client.page(0, 500);

        server.verify();
        assertThat(result.nextAfter()).isEqualTo(8);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.skipped()).isZero();
        assertThat(result.events()).hasSize(2);
        LoginEvent first = result.events().getFirst();
        assertThat(first.eventId()).hasToString(LoginFixtures.eventId(7));
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-09-24T13:59:00.123Z"));
        assertThat(first.outcome()).isEqualTo("failure");
        assertThat(first.clientIp()).isEqualTo("203.0.113.7");
        assertThat(first.ipSource()).isEqualTo("cf-connecting-ip");
        assertThat(first.usernameHmac()).isEqualTo(HMAC_A);
        assertThat(first.subject()).isNull();
        assertThat(first.userAgent()).isEqualTo("curl/8");
        LoginEvent second = result.events().get(1);
        assertThat(second.clientIp()).isNull();
        assertThat(second.subject()).isEqualTo("dominic");
        assertThat(second.userAgent()).isNull();
    }

    @Test
    void cachesTheTokenUntilShortlyBeforeExpiry() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        expectPage(0, "tok-1", page(List.of(), 0, false));
        expectPage(0, "tok-1", page(List.of(), 0, false));
        expectToken("tok-2");
        expectPage(0, "tok-2", page(List.of(), 0, false));

        client.page(0, 500);
        clock.advance(Duration.ofSeconds(839)); // 900 s lifetime - 60 s skew - 1 s
        client.page(0, 500);
        clock.advance(Duration.ofSeconds(2));
        client.page(0, 500);

        server.verify();
    }

    @Test
    void unauthorizedPageIsRetriedOnceWithAFreshToken() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("tok-2");
        expectPage(0, "tok-2", page(List.of(), 0, false));

        assertThat(client.page(0, 500).events()).isEmpty();
        server.verify();
    }

    @Test
    void unauthorizedRetryFailsWithCredentialsAndDropsTheToken() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("tok-2");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("tok-3");
        expectPage(0, "tok-3", page(List.of(), 0, false));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
        client.page(0, 500);
        server.verify();
    }

    @Test
    void forbiddenPageDropsTheCachedToken() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectToken("tok-2");
        expectPage(0, "tok-2", page(List.of(), 0, false));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
        client.page(0, 500);
        server.verify();
    }

    @Test
    void unknownFieldsAreIgnored() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        String withExtra = event(3, "2026-09-24T13:00:00Z", "failure", "203.0.113.7", "remote-addr", HMAC_A, null, null)
                .replace("\"id\":3,", "\"id\":3,\"recordedAt\":\"2026-09-24T13:00:01Z\",\"futureField\":{\"a\":1},");
        expectPage(0, "tok-1", "{\"events\":[" + withExtra + "],\"nextAfter\":3,\"hasMore\":false,\"apiVersion\":2}");

        LoginEventPage result = client.page(0, 500);

        assertThat(result.events()).hasSize(1);
        assertThat(result.skipped()).isZero();
        assertThat(withExtra).contains("futureField");
    }

    @Test
    void blankSecretFailsWithCredentialsWithoutCallingOut() {
        AuthServiceClient client = client("");
        server.expect(never(), requestTo(TOKEN_URL));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e).isNotInstanceOf(CollectorWarning.class);
                    assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS);
                });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400, credentials", "401, credentials", "429, rate_limited", "500, upstream", "503, upstream"})
    void tokenErrorsMapToStatusCodes(int status, String code) {
        AuthServiceClient client = client(SECRET);
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.valueOf(status))
                .body("{\"error\":\"invalid_client\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e).isNotInstanceOf(CollectorWarning.class);
                    assertThat(e.code().value()).isEqualTo(code);
                    assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("invalid_client");
                });
    }

    @ParameterizedTest
    @CsvSource({"400, upstream", "403, credentials", "429, rate_limited", "500, upstream", "502, upstream"})
    void pageErrorsMapToStatusCodes(int status, String code) {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e).isNotInstanceOf(CollectorWarning.class);
                    assertThat(e.code().value()).isEqualTo(code);
                });
    }

    @Test
    void disabledOutboxIsAnUpstreamWarning() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"status\":503,\"error\":\"Login-event capture is disabled\",\"timestamp\":\"2026-09-24T14:00:00Z\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorWarning.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));
    }

    @Test
    void ioErrorsAndMalformedBodiesAreUpstream() {
        AuthServiceClient client = client(SECRET);
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new IOException("connection refused")));
        assertThatThrownBy(() -> client.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));

        AuthServiceClient second = client(SECRET);
        expectToken("tok-1");
        server.expect(requestTo(EVENTS_URL + "?after=0&limit=500"))
                .andRespond(withSuccess("{\"events\":\"nope\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> second.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));

        AuthServiceClient third = client(SECRET);
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> third.page(0, 500))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));
    }

    @Test
    void invalidRowsAreSkippedAndNormalised() {
        AuthServiceClient client = client(SECRET);
        expectToken("tok-1");
        String longAgent = "x".repeat(600);
        expectPage(0, "tok-1", page(List.of(
                event(1, "2026-09-24T13:00:00Z", "denied", "203.0.113.7", "remote-addr", HMAC_A, null, null),
                event(2, "not-a-time", "failure", "203.0.113.7", "remote-addr", HMAC_A, null, null),
                event(3, "2026-09-24T13:00:00Z", "failure", "203.0.113.7", "x-forwarded-for", HMAC_A, null, null),
                event(4, "2026-09-24T13:00:00Z", "failure", "203.0.113.7", "remote-addr", "ABC", null, null),
                // Valid, but the IP is junk, the subject must be dropped for a failure and the agent is too long.
                event(5, "2026-09-24T13:00:00Z", "failure", "not-an-ip", "remote-addr", HMAC_A, "dominic", longAgent),
                // IPv4-mapped IPv6 is stored as IPv4.
                event(6, "2026-09-24T13:00:00Z", "locked", "::ffff:198.51.100.9", "cf-connecting-ip", HMAC_A, null, null)),
                6, false));

        LoginEventPage result = client.page(0, 500);

        assertThat(result.skipped()).isEqualTo(4);
        assertThat(result.nextAfter()).isEqualTo(6);
        assertThat(result.events()).hasSize(2);
        LoginEvent junk = result.events().getFirst();
        assertThat(junk.clientIp()).isNull();
        assertThat(junk.subject()).isNull();
        assertThat(junk.userAgent()).hasSize(512);
        assertThat(result.events().get(1).clientIp()).isEqualTo("198.51.100.9");
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
