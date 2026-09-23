package ch.furchert.homelab.data.netmon.reputation;

import ch.furchert.homelab.data.config.AbuseIpDbProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AbuseIPDB {@code /api/v2/check} (docs/060 §4.5). Reads {@code data.abuseConfidenceScore} and
 * {@code data.totalReports} only. The IP travels as a URI template variable, never inside the template,
 * and neither the key nor the IP is ever logged or put into an error message.
 */
@Component
public class AbuseIpDbClient {

    /** Result of one check. */
    public record Reputation(int score, int reports) {
    }

    private final RestClient restClient;
    private final AbuseIpDbProperties properties;
    private final JsonMapper jsonMapper;

    public AbuseIpDbClient(RestClient netmonRestClient, AbuseIpDbProperties properties, JsonMapper jsonMapper) {
        this.restClient = netmonRestClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public Reputation check(String ip) {
        if (!properties.hasKey()) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "AbuseIPDB key not configured");
        }
        Response response;
        try {
            response = restClient.get()
                    .uri(properties.url() + "?ipAddress={ip}&maxAgeInDays=90", ip)
                    .header("Key", properties.apiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(), res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "AbuseIPDB unreachable");
        }
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "AbuseIPDB rejected the key (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new CollectorException(ErrorCode.RATE_LIMITED, "AbuseIPDB rate limit (HTTP 429)");
        }
        if (status < 200 || status >= 300) {
            throw new CollectorException(ErrorCode.UPSTREAM, "AbuseIPDB answered HTTP " + status);
        }
        try {
            JsonNode data = jsonMapper.readTree(response.body()).path("data");
            JsonNode score = data.path("abuseConfidenceScore");
            JsonNode reports = data.path("totalReports");
            if (!score.isNumber() || !reports.isNumber()) {
                throw new CollectorException(ErrorCode.UPSTREAM, "AbuseIPDB response lacks score or reports");
            }
            return new Reputation(Math.clamp(score.asInt(), 0, 100), Math.max(reports.asInt(), 0));
        } catch (JacksonException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "AbuseIPDB returned malformed JSON");
        }
    }

    private record Response(int status, byte[] body) {
    }
}
