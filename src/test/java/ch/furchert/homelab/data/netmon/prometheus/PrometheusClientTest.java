package ch.furchert.homelab.data.netmon.prometheus;

import ch.furchert.homelab.data.config.PrometheusProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** POST /api/v1/query (docs/060 §4.6) and the error mapping to the status API codes. */
class PrometheusClientTest {

    private static final String URL = "http://prometheus.test:9090/api/v1/query";
    private static final Instant TIME = Instant.parse("2026-09-24T10:04:00Z");

    private MockRestServiceServer server;

    private PrometheusClient client() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new PrometheusClient(builder.build(), new PrometheusProperties("http://prometheus.test:9090/"),
                JsonMapper.builder().build());
    }

    @Test
    void postsQueryAndUnixTimeAsFormFields() {
        PrometheusClient client = client();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(org.springframework.util.MultiValueMap.fromSingleValue(Map.of(
                        "query", "max by (node) (up)", "time", Long.toString(TIME.getEpochSecond())))))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"node":"raspi5"},"value":[1758708240,"1758708005"]},
                          {"metric":{"node":"mba1"},"value":[1758708240,"NaN"]}]}}
                        """, MediaType.APPLICATION_JSON));

        List<PrometheusSample> samples = client.query("max by (node) (up)", TIME);

        server.verify();
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).label("node")).isEqualTo("raspi5");
        assertThat(samples.get(0).value()).isEqualTo(1_758_708_005d);
        assertThat(samples.get(1).value()).isNaN();
    }

    @Test
    void emptyVectorIsNoError() {
        PrometheusClient client = client();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.query("up", TIME)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"503,upstream", "500,upstream", "429,rate_limited", "401,credentials", "403,credentials",
            "400,internal", "422,internal"})
    void mapsHttpStatuses(int status, String code) {
        PrometheusClient client = client();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.query("up", TIME))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code().value()).isEqualTo(code));
    }

    @Test
    void mapsIoFailuresToUpstream() {
        PrometheusClient client = client();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> client.query("up", TIME))
                .isInstanceOfSatisfying(CollectorException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM);
                    assertThat(e.getMessage()).isEqualTo("Prometheus unreachable");
                });
    }

    @Test
    void mapsErrorBodiesAndUnexpectedShapes() {
        assertCode("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}", ErrorCode.INTERNAL);
        assertCode("{\"status\":\"error\",\"errorType\":\"timeout\",\"error\":\"query timed out\"}", ErrorCode.UPSTREAM);
        assertCode("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}", ErrorCode.UPSTREAM);
        assertCode("not json", ErrorCode.UPSTREAM);
        assertCode("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1,\"x\"]}]}}",
                ErrorCode.UPSTREAM);
    }

    private void assertCode(String body, ErrorCode code) {
        PrometheusClient client = client();
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.query("up", TIME))
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void parsesSpecialValues() {
        assertThat(PrometheusClient.parseValue("+Inf")).isInfinite();
        assertThat(PrometheusClient.parseValue("-Inf")).isNegative().isInfinite();
        assertThat(PrometheusClient.parseValue("4")).isEqualTo(4d);
    }

    @Test
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> new PrometheusProperties(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
