package ch.furchert.homelab.data.netmon.prometheus;

import ch.furchert.homelab.data.config.PrometheusProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instant queries against the cluster Prometheus (docs/060 §4.6): {@code POST /api/v1/query} with the form
 * fields {@code query} and {@code time} (unix seconds), 10 s timeouts from {@code netmonRestClient}. The
 * query travels in the body, so no label value ever appears in a URL. Errors map to the status API codes:
 * I/O, 5xx, malformed JSON and non-vector results → {@code upstream}; 429 → {@code rate_limited};
 * 401/403 → {@code credentials}; 400/422 ({@code bad_data}: a broken query is a data-service bug) →
 * {@code internal}. Neither the query nor the result is logged.
 */
@Component
public class PrometheusClient {

    private final RestClient restClient;
    private final PrometheusProperties properties;
    private final JsonMapper jsonMapper;

    public PrometheusClient(RestClient netmonRestClient, PrometheusProperties properties, JsonMapper jsonMapper) {
        this.restClient = netmonRestClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** Evaluates {@code promql} at {@code time}; an empty vector is an empty list, not an error. */
    public List<PrometheusSample> query(String promql, Instant time) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", promql);
        form.add("time", Long.toString(time.getEpochSecond()));

        Response response;
        try {
            response = restClient.post()
                    .uri(properties.queryUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(), res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus unreachable");
        }
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "Prometheus rejected the request (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new CollectorException(ErrorCode.RATE_LIMITED, "Prometheus rate limit (HTTP 429)");
        }
        if (status == 400 || status == 422) {
            throw new CollectorException(ErrorCode.INTERNAL, "Prometheus rejected the query (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus answered HTTP " + status);
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus returned malformed JSON");
        }
        if (!"success".equals(root.path("status").asString(""))) {
            boolean badData = "bad_data".equals(root.path("errorType").asString(""));
            throw new CollectorException(badData ? ErrorCode.INTERNAL : ErrorCode.UPSTREAM,
                    badData ? "Prometheus rejected the query" : "Prometheus returned an error");
        }
        JsonNode data = root.path("data");
        if (!"vector".equals(data.path("resultType").asString(""))) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus returned no instant vector");
        }
        List<PrometheusSample> samples = new ArrayList<>();
        for (JsonNode element : data.path("result")) {
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> label : element.path("metric").properties()) {
                labels.put(label.getKey(), label.getValue().asString(""));
            }
            JsonNode value = element.path("value");
            if (!value.isArray() || value.size() != 2) {
                throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus returned a malformed sample");
            }
            samples.add(new PrometheusSample(labels, parseValue(value.get(1).asString(""))));
        }
        return samples;
    }

    /** Prometheus encodes sample values as strings, including {@code NaN}, {@code +Inf} and {@code -Inf}. */
    static double parseValue(String raw) {
        return switch (raw) {
            case "NaN" -> Double.NaN;
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    throw new CollectorException(ErrorCode.UPSTREAM, "Prometheus returned a malformed sample value");
                }
            }
        };
    }

    private record Response(int status, byte[] body) {
    }
}
