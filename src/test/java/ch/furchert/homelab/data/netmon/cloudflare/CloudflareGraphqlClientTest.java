package ch.furchert.homelab.data.netmon.cloudflare;

import ch.furchert.homelab.data.config.CloudflareProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.URL;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.event;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.eventsResponse;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.group;
import static ch.furchert.homelab.data.netmon.cloudflare.CloudflareFixtures.groupsResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CloudflareGraphqlClientTest {

    private static final Instant SINCE = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-09-23T10:58:00Z");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private CloudflareGraphqlClient client(String token, String zone) {
        return new CloudflareGraphqlClient(builder.build(),
                new CloudflareProperties(URL, token, zone, 5000, 1000, 20, 60), json);
    }

    private CloudflareGraphqlClient client() {
        return client("test-token", "zone-123");
    }

    @Test
    void queryA_sendsBearerAndVariablesAndParsesRows() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.query", containsString("httpRequestsAdaptiveGroups")))
                .andExpect(jsonPath("$.variables.zoneTag").value("zone-123"))
                .andExpect(jsonPath("$.variables.since").value("2026-09-23T10:00:00Z"))
                .andExpect(jsonPath("$.variables.until").value("2026-09-23T10:58:00Z"))
                .andExpect(jsonPath("$.variables.limit").value(3))
                .andRespond(withSuccess(groupsResponse(
                        group("203.0.113.7", "furchert.ch", "GET", "/de", 200, 120, 1),
                        group("2001:db8::1", "auth.furchert.ch", "POST", "/login", 401, 40, 10)), MediaType.APPLICATION_JSON));

        Page<RequestGroup> page = client().requestGroups(SINCE, UNTIL, 3);

        server.verify();
        assertThat(page.full()).isFalse();
        assertThat(page.items()).containsExactly(
                new RequestGroup("203.0.113.7", "DE", "furchert.ch", "GET", "/de", 200, 120, 1.0),
                new RequestGroup("2001:db8:0:0:0:0:0:1", "DE", "auth.furchert.ch", "POST", "/login", 401, 40, 10.0));
    }

    @Test
    void queryA_fullPageIsFlaggedEvenIfRowsAreDropped() {
        server.expect(requestTo(URL)).andRespond(withSuccess(groupsResponse(
                group("203.0.113.7", "furchert.ch", "GET", "/", 200, 1, 1),
                group("not-an-ip", "furchert.ch", "GET", "/", 200, 1, 1)), MediaType.APPLICATION_JSON));

        Page<RequestGroup> page = client().requestGroups(SINCE, UNTIL, 2);

        assertThat(page.full()).isTrue();
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void queryA_normalisesLongPathsAndOddDimensions() {
        String longPath = "/" + "a".repeat(2000);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"data":{"viewer":{"zones":[{"httpRequestsAdaptiveGroups":[
                  {"count":5,"avg":{"sampleInterval":1},"dimensions":{"clientIP":"203.0.113.7","clientCountryName":"T1",
                   "clientRequestHTTPHost":"furchert.ch",
                   "clientRequestHTTPMethodName":"GET","clientRequestPath":"%s","edgeResponseStatus":404}}]}]}}}
                """.formatted(longPath), MediaType.APPLICATION_JSON));

        RequestGroup group = client().requestGroups(SINCE, UNTIL, 10).items().getFirst();

        assertThat(group.path()).hasSize(1024);
        assertThat(group.country()).isEqualTo("T1");
    }

    @Test
    void queryA_requestsNoAsnDimensions() {
        // Settings probe 2026-09-24: httpRequestsAdaptiveGroups has no ASN dimensions; asking for one fails the query.
        assertThat(CloudflareGraphqlClient.QUERY_GROUPS).doesNotContain("clientAsn").doesNotContain("clientASNDescription");
        assertThat(CloudflareGraphqlClient.QUERY_FIREWALL).contains("clientAsn clientASNDescription");
    }

    @Test
    void queryB_usesDatetimeGeqOrGtAndParsesEvents() {
        Instant at = Instant.parse("2026-09-23T10:01:02Z");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query", containsString("datetime_geq: $since")))
                .andExpect(jsonPath("$.query", containsString("orderBy: [datetime_ASC]")))
                .andRespond(withSuccess(eventsResponse(List.of(event(at, "8c1", "198.51.100.9", "block", null))),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.query", containsString("datetime_gt: $since")))
                .andExpect(jsonPath("$.query", not(containsString("datetime_geq"))))
                .andRespond(withSuccess(eventsResponse(List.of()), MediaType.APPLICATION_JSON));

        Page<FirewallEvent> first = client().firewallEvents(SINCE, false, UNTIL, 1);
        Page<FirewallEvent> second = client().firewallEvents(at, true, UNTIL, 1);

        server.verify();
        assertThat(first.full()).isTrue();
        assertThat(first.items()).containsExactly(new FirewallEvent(at, "8c1", "198.51.100.9", "US", 14061,
                "DIGITALOCEAN-ASN", "block", "firewallManaged", null, "furchert.ch", "GET", "/wp-login.php", "curl/8.0"));
        assertThat(second.items()).isEmpty();
        assertThat(second.full()).isFalse();
    }

    @Test
    void missingCredentials_failWithoutCallingOut() {
        assertThatThrownBy(() -> client("", "zone-123").requestGroups(SINCE, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
        assertThatThrownBy(() -> client("token", " ").firewallEvents(SINCE, false, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
        server.verify();
    }

    @Test
    void httpStatusesMapToErrorCodes() {
        assertCode(HttpStatus.UNAUTHORIZED, ErrorCode.CREDENTIALS);
        assertCode(HttpStatus.FORBIDDEN, ErrorCode.CREDENTIALS);
        assertCode(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
        assertCode(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM);
        assertCode(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM);
    }

    @Test
    void graphqlErrorsAreFailuresWithAFixedMessage() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                CloudflareFixtures.errorsResponse("unknown field clientASNDescription for 203.0.113.7"),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().requestGroups(SINCE, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM);
                    assertThat(e.getMessage()).isEqualTo("Cloudflare GraphQL returned errors");
                });
    }

    @Test
    void graphqlAuthorizationErrorsAreCredentials() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                CloudflareFixtures.errorsResponse("not authorized for that account"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().firewallEvents(SINCE, false, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
    }

    @Test
    void unknownZoneIsCredentials() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"data\":{\"viewer\":{\"zones\":[]}}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().requestGroups(SINCE, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
    }

    @Test
    void malformedJsonIsUpstream() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>oops", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client().requestGroups(SINCE, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));
    }

    @Test
    void requestBodyNeverCarriesTheToken() {
        server.expect(requestTo(URL))
                .andExpect(content().string(not(containsString("test-token"))))
                .andRespond(withSuccess(groupsResponse(), MediaType.APPLICATION_JSON));

        client().requestGroups(SINCE, UNTIL, 10);
        server.verify();
    }

    @Test
    void loggedUpstreamMessagesAreIpMasked() {
        assertThat(CloudflareGraphqlClient.redact("bad value 203.0.113.7 and 2001:db8::1 in field clientIP"))
                .isEqualTo("bad value <ip> and <ip> in field clientIP");
    }

    private void assertCode(HttpStatus status, ErrorCode expected) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        s.expect(requestTo(URL)).andRespond(withStatus(status).body("{\"errors\":[{\"message\":\"x\"}]}"));
        CloudflareGraphqlClient c = new CloudflareGraphqlClient(b.build(),
                new CloudflareProperties(URL, "t", "z", 5000, 1000, 20, 60), json);
        assertThatThrownBy(() -> c.requestGroups(SINCE, UNTIL, 10))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e.code()).isEqualTo(expected);
                    assertThat(e.getMessage()).contains(String.valueOf(status.value()));
                });
    }
}
