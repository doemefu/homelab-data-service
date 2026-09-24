package ch.furchert.homelab.data.netmon.cloudflare;

import ch.furchert.homelab.data.config.CloudflareProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.ip.IpAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cloudflare GraphQL Analytics client for docs/060 §4.2 queries A (request groups) and B (firewall
 * events). Errors map to the status API codes: missing token/zone and HTTP 401/403 → {@code credentials},
 * 429 → {@code rate_limited}, 5xx, I/O and GraphQL {@code errors[]} → {@code upstream}.
 * Never logs the token, the Authorization header or the query variables (§10).
 */
@Component
public class CloudflareGraphqlClient {

    static final int MAX_PATH = 1024;
    static final int MAX_USER_AGENT = 512;
    private static final int MAX_LOGGED_ERROR = 200;
    /** IPv4 literals and IPv6-looking tokens (two or more colons) are masked before an upstream message is logged. */
    private static final Pattern IP_LIKE = Pattern.compile(
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b|\\b[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}\\b");

    private static final Logger log = LoggerFactory.getLogger(CloudflareGraphqlClient.class);

    /**
     * docs/060 §4.2 query A without {@code clientAsn}/{@code clientASNDescription}: the zone's
     * {@code httpRequestsAdaptiveGroups} does not offer them (settings probe 2026-09-24), and requesting an
     * unavailable field fails the whole query. ASN comes from query B via {@code ip_enrichment}.
     */
    static final String QUERY_GROUPS = """
            query InboundGroups($zoneTag: string!, $since: Time!, $until: Time!, $limit: uint64!) {
              viewer { zones(filter: {zoneTag: $zoneTag}) {
                httpRequestsAdaptiveGroups(limit: $limit,
                    filter: {datetime_geq: $since, datetime_lt: $until}, orderBy: [count_DESC]) {
                  count
                  avg { sampleInterval }
                  dimensions { clientIP clientCountryName
                               clientRequestHTTPHost clientRequestHTTPMethodName clientRequestPath edgeResponseStatus }
                } } }
            }""";

    private static final String FIREWALL_TEMPLATE = """
            query FirewallEvents($zoneTag: string!, $since: Time!, $until: Time!, $limit: uint64!) {
              viewer { zones(filter: {zoneTag: $zoneTag}) {
                firewallEventsAdaptive(limit: $limit,
                    filter: {%s: $since, datetime_lt: $until}, orderBy: [datetime_ASC]) {
                  datetime rayName clientIP clientCountryName clientAsn clientASNDescription
                  action source ruleId clientRequestHTTPHost clientRequestHTTPMethodName clientRequestPath userAgent
                } } }
            }""";

    /** Keyset page start inclusive ({@code datetime_geq}), the normal case. */
    static final String QUERY_FIREWALL = FIREWALL_TEMPLATE.formatted("datetime_geq");
    /** Keyset page start exclusive ({@code datetime_gt}), used when a full page shares one timestamp. */
    static final String QUERY_FIREWALL_AFTER = FIREWALL_TEMPLATE.formatted("datetime_gt");

    private final RestClient restClient;
    private final CloudflareProperties properties;
    private final JsonMapper jsonMapper;

    public CloudflareGraphqlClient(RestClient netmonRestClient, CloudflareProperties properties, JsonMapper jsonMapper) {
        this.restClient = netmonRestClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** Query A for {@code [since, until)}. A full page (exactly {@code limit} rows) means "possibly truncated". */
    public Page<RequestGroup> requestGroups(Instant since, Instant until, int limit) {
        JsonNode rows = execute(QUERY_GROUPS, since, until, limit).path("httpRequestsAdaptiveGroups");
        List<RequestGroup> groups = new ArrayList<>(rows.size());
        int skipped = 0;
        for (JsonNode row : rows) {
            JsonNode d = row.path("dimensions");
            String ip = IpAddresses.canonical(text(d, "clientIP")).orElse(null);
            if (ip == null) {
                skipped++;
                continue;
            }
            groups.add(new RequestGroup(
                    ip,
                    country(d),
                    nonNull(text(d, "clientRequestHTTPHost")),
                    nonNull(text(d, "clientRequestHTTPMethodName")),
                    truncate(nonNull(text(d, "clientRequestPath")), MAX_PATH),
                    d.path("edgeResponseStatus").asInt(0),
                    row.path("count").asLong(0),
                    Math.max(1.0, row.path("avg").path("sampleInterval").asDouble(1.0))));
        }
        if (skipped > 0) {
            log.warn("[cloudflare] skipped {} request groups without a valid client IP", skipped);
        }
        return new Page<>(groups, rows.size() >= limit);
    }

    /**
     * Query B for {@code [since, until)} ordered by {@code datetime} ascending, or {@code (since, until)}
     * when {@code exclusiveStart} is set.
     */
    public Page<FirewallEvent> firewallEvents(Instant since, boolean exclusiveStart, Instant until, int limit) {
        JsonNode rows = execute(exclusiveStart ? QUERY_FIREWALL_AFTER : QUERY_FIREWALL, since, until, limit)
                .path("firewallEventsAdaptive");
        List<FirewallEvent> events = new ArrayList<>(rows.size());
        int skipped = 0;
        for (JsonNode row : rows) {
            String ip = IpAddresses.canonical(text(row, "clientIP")).orElse(null);
            Instant occurredAt = instant(text(row, "datetime"));
            String rayName = text(row, "rayName");
            if (ip == null || occurredAt == null || rayName == null) {
                skipped++;
                continue;
            }
            events.add(new FirewallEvent(
                    occurredAt,
                    rayName,
                    ip,
                    country(row),
                    asn(row),
                    text(row, "clientASNDescription"),
                    nonNull(text(row, "action")),
                    nonNull(text(row, "source")),
                    text(row, "ruleId"),
                    text(row, "clientRequestHTTPHost"),
                    text(row, "clientRequestHTTPMethodName"),
                    truncate(text(row, "clientRequestPath"), MAX_PATH),
                    truncate(text(row, "userAgent"), MAX_USER_AGENT)));
        }
        if (skipped > 0) {
            log.warn("[cloudflare] skipped {} firewall events without ip, datetime or rayName", skipped);
        }
        return new Page<>(events, rows.size() >= limit);
    }

    private JsonNode execute(String query, Instant since, Instant until, int limit) {
        if (!properties.hasCredentials()) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "Cloudflare API token or zone id not configured");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("zoneTag", properties.zoneId());
        variables.put("since", rfc3339(since));
        variables.put("until", rfc3339(until));
        variables.put("limit", limit);
        String body = jsonMapper.writeValueAsString(Map.of("query", query, "variables", variables));

        Response response;
        try {
            response = restClient.post()
                    .uri(properties.graphqlUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(), res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Cloudflare GraphQL unreachable");
        }
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "Cloudflare GraphQL rejected the token (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new CollectorException(ErrorCode.RATE_LIMITED, "Cloudflare GraphQL rate limit (HTTP 429)");
        }
        if (status < 200 || status >= 300) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Cloudflare GraphQL answered HTTP " + status);
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "Cloudflare GraphQL returned malformed JSON");
        }
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String message = truncate(errors.get(0).path("message").asString(""), MAX_LOGGED_ERROR);
            // The upstream message goes to the log only, IP-masked; last_error gets a fixed text (no echoed data).
            log.warn("[cloudflare] GraphQL errors: count={} first={}", errors.size(), redact(message));
            String lower = message.toLowerCase();
            boolean auth = lower.contains("authentication") || lower.contains("authoriz") || lower.contains("permission");
            throw new CollectorException(auth ? ErrorCode.CREDENTIALS : ErrorCode.UPSTREAM,
                    auth ? "Cloudflare GraphQL denied access" : "Cloudflare GraphQL returned errors");
        }
        JsonNode zones = root.path("data").path("viewer").path("zones");
        if (!zones.isArray() || zones.isEmpty()) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "Cloudflare GraphQL returned no zone for the configured zone id");
        }
        return zones.get(0);
    }

    private static String rfc3339(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text.isEmpty() ? null : text;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    static String redact(String message) {
        return IP_LIKE.matcher(message).replaceAll("<ip>");
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String country(JsonNode node) {
        String country = text(node, "clientCountryName");
        return country != null && country.length() == 2 ? country : null;
    }

    private static Integer asn(JsonNode node) {
        String asn = text(node, "clientAsn");
        if (asn == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(asn.strip());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private record Response(int status, byte[] body) {
    }
}
