package ch.furchert.homelab.data.netmon.reputation;

import ch.furchert.homelab.data.config.AbuseIpDbProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AbuseIpDbClientTest {

    static final String URL = "https://abuseipdb.test/api/v2/check";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AbuseIpDbClient client = new AbuseIpDbClient(builder.build(),
            new AbuseIpDbProperties(URL, "secret-key", 200, 10), JsonMapper.builder().build());

    @Test
    void readsScoreAndReportsOnly() {
        server.expect(requestTo(URL + "?ipAddress=2001%3Adb8%3A%3A1&maxAgeInDays=90"))
                .andExpect(header("Key", "secret-key"))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess("{\"data\":{\"abuseConfidenceScore\":101,\"totalReports\":3,\"isp\":\"x\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.check("2001:db8::1")).isEqualTo(new AbuseIpDbClient.Reputation(100, 3));
    }

    @Test
    void errorsMapToCodesWithoutEchoingTheIpOrKey() {
        server.expect(requestTo(URL + "?ipAddress=203.0.113.7&maxAgeInDays=90")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URL + "?ipAddress=203.0.113.7&maxAgeInDays=90")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(URL + "?ipAddress=203.0.113.7&maxAgeInDays=90"))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertCode(ErrorCode.CREDENTIALS);
        assertCode(ErrorCode.UPSTREAM);
        assertCode(ErrorCode.UPSTREAM);
    }

    @Test
    void noKeyNeverCallsOut() {
        AbuseIpDbClient keyless = new AbuseIpDbClient(builder.build(), new AbuseIpDbProperties(URL, "", 200, 10),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> keyless.check("203.0.113.7"))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CREDENTIALS));
        server.verify();
    }

    private void assertCode(ErrorCode expected) {
        assertThatThrownBy(() -> client.check("203.0.113.7"))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e.code()).isEqualTo(expected);
                    assertThat(e.getMessage()).doesNotContain("203.0.113.7").doesNotContain("secret-key");
                });
    }
}
