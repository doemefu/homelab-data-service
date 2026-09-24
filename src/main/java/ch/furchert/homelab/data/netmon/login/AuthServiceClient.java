package ch.furchert.homelab.data.netmon.login;

import ch.furchert.homelab.data.config.AuthServiceProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.ip.IpAddresses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * auth-service client for the login-event pull (docs/060 §7.6, auth-service INTERFACES.md "login-event pull"):
 * a {@code client_credentials} token with {@code scope=login-events:read}, cached until {@code expires_in − 60 s},
 * and {@code GET /api/v1/login-events?after&limit}. Errors map to the status API codes:
 * <ul>
 *   <li>blank secret, token 400/401, page 403 → {@code credentials}; a page 401 is retried once with a fresh token
 *       and is {@code credentials} only if the retry is rejected too. Page 401 and 403 drop the cached token, so a
 *       fixed scope or secret takes effect on the next call.</li>
 *   <li>429 → {@code rate_limited}; other non-2xx, I/O and malformed JSON → {@code upstream};</li>
 *   <li>page 503 (the producer's feature is disabled) → {@link CollectorWarning} {@code upstream}, i.e. "no data yet"
 *       (docs/060 §7.2).</li>
 * </ul>
 * Never logs or stores the secret, the token, response bodies, IPs or usernames.
 */
@Component
public class AuthServiceClient {

    static final String SCOPE = "login-events:read";
    static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);
    /** Used only if the token response has no usable {@code expires_in}. */
    static final Duration DEFAULT_TOKEN_LIFETIME = Duration.ofMinutes(5);
    static final int MAX_USER_AGENT = 512;
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static final Set<String> OUTCOMES = Set.of("success", "failure", "locked");
    private static final Set<String> IP_SOURCES = Set.of("cf-connecting-ip", "remote-addr");
    private static final Pattern HMAC = Pattern.compile("[0-9a-f]{64}");

    private final RestClient restClient;
    private final AuthServiceProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedUntil;

    public AuthServiceClient(RestClient netmonRestClient, AuthServiceProperties properties, JsonMapper jsonMapper,
                             Clock clock) {
        this.restClient = netmonRestClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** One outbox page after {@code after} (exclusive). */
    public LoginEventPage page(long after, int limit) {
        String uri = UriComponentsBuilder.fromUriString(properties.loginEventsUrl())
                .queryParam("after", after)
                .queryParam("limit", limit)
                .build()
                .toUriString();
        Response response = fetch(uri, token());
        if (response.status() == 401) {
            // A token revoked or signed with a rotated key: one retry with a fresh token in the same run.
            invalidateToken();
            response = fetch(uri, token());
            if (response.status() == 401) {
                invalidateToken();
                throw new CollectorException(ErrorCode.CREDENTIALS, "auth-service rejected the access token (HTTP 401)");
            }
        }
        int status = response.status();
        if (status == 403) {
            invalidateToken();
            throw new CollectorException(ErrorCode.CREDENTIALS,
                    "auth-service denied the login-event outbox (HTTP 403, scope login-events:read missing)");
        }
        if (status == 503) {
            throw new CollectorWarning(ErrorCode.UPSTREAM, "auth-service login-event outbox is disabled (HTTP 503)");
        }
        checkStatus(status, "login-event outbox");
        return parsePage(readTree(response, "login-event outbox"));
    }

    private Response fetch(String uri, String token) {
        return call(() -> restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, res) -> new Response(res.getStatusCode().value(),
                        res.getBody().readNBytes(MAX_BODY_BYTES + 1))), "login-event outbox");
    }

    /** Forgets the cached access token, e.g. after the producer rejected it. */
    public synchronized void invalidateToken() {
        cachedToken = null;
        cachedUntil = null;
    }

    private synchronized String token() {
        if (!properties.hasCredentials()) {
            throw new CollectorException(ErrorCode.CREDENTIALS, "auth-service client secret not configured");
        }
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedUntil)) {
            return cachedToken;
        }
        String form = "grant_type=client_credentials&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        Response response = call(() -> restClient.post()
                .uri(properties.tokenUrl())
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .exchange((request, res) -> new Response(res.getStatusCode().value(),
                        res.getBody().readNBytes(MAX_BODY_BYTES + 1))), "token endpoint");
        int status = response.status();
        if (status == 400 || status == 401) {
            throw new CollectorException(ErrorCode.CREDENTIALS,
                    "auth-service rejected the client credentials (HTTP " + status + ")");
        }
        checkStatus(status, "token endpoint");
        JsonNode root = readTree(response, "token endpoint");
        JsonNode accessToken = root.path("access_token");
        if (!accessToken.isString() || accessToken.asString().isBlank()) {
            throw new CollectorException(ErrorCode.UPSTREAM, "auth-service token response has no access_token");
        }
        JsonNode expiresIn = root.path("expires_in");
        Duration lifetime = expiresIn.isNumber() && expiresIn.asLong() > 0
                ? Duration.ofSeconds(expiresIn.asLong()) : DEFAULT_TOKEN_LIFETIME;
        Duration usable = lifetime.minus(EXPIRY_SKEW);
        cachedToken = accessToken.asString();
        cachedUntil = now.plus(usable.isNegative() ? Duration.ZERO : usable);
        return cachedToken;
    }

    /** RFC 6749 §2.3.1: id and secret are form-urlencoded before Base64; Spring Authorization Server URL-decodes them. */
    private String basicAuth() {
        String pair = URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(properties.clientSecret(), StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private LoginEventPage parsePage(JsonNode root) {
        JsonNode events = root.path("events");
        JsonNode nextAfter = root.path("nextAfter");
        JsonNode hasMore = root.path("hasMore");
        if (!events.isArray() || !nextAfter.isIntegralNumber() || nextAfter.asLong() < 0 || !hasMore.isBoolean()) {
            throw new CollectorException(ErrorCode.UPSTREAM, "auth-service returned a malformed login-event page");
        }
        List<LoginEvent> parsed = new ArrayList<>(events.size());
        int skipped = 0;
        for (JsonNode row : events) {
            LoginEvent event = parseEvent(row);
            if (event == null) {
                skipped++;
            } else {
                parsed.add(event);
            }
        }
        return new LoginEventPage(parsed, nextAfter.asLong(), hasMore.asBoolean(), skipped);
    }

    /** {@code null} for a row that does not satisfy the §3.3 constraints. */
    private static LoginEvent parseEvent(JsonNode row) {
        UUID eventId = uuid(text(row, "eventId"));
        Instant occurredAt = instant(text(row, "occurredAt"));
        String outcome = text(row, "outcome");
        String ipSource = text(row, "ipSource");
        String hmac = text(row, "usernameHmac");
        if (eventId == null || occurredAt == null || outcome == null || !OUTCOMES.contains(outcome)
                || ipSource == null || !IP_SOURCES.contains(ipSource) || hmac == null || !HMAC.matcher(hmac).matches()) {
            return null;
        }
        String clientIp = IpAddresses.canonical(text(row, "clientIp")).orElse(null);
        String subject = "success".equals(outcome) ? text(row, "subject") : null;
        String userAgent = text(row, "userAgent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        return new LoginEvent(row.path("id").asLong(0), eventId, occurredAt, outcome, clientIp, ipSource, hmac,
                subject, userAgent);
    }

    private Response call(java.util.function.Supplier<Response> exchange, String what) {
        try {
            return exchange.get();
        } catch (ResourceAccessException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "auth-service " + what + " unreachable");
        }
    }

    private static void checkStatus(int status, String what) {
        if (status == 429) {
            throw new CollectorException(ErrorCode.RATE_LIMITED, "auth-service " + what + " rate limit (HTTP 429)");
        }
        if (status < 200 || status >= 300) {
            throw new CollectorException(ErrorCode.UPSTREAM, "auth-service " + what + " answered HTTP " + status);
        }
    }

    private JsonNode readTree(Response response, String what) {
        if (response.body().length > MAX_BODY_BYTES) {
            throw new CollectorException(ErrorCode.UPSTREAM,
                    "auth-service " + what + " response exceeds " + MAX_BODY_BYTES + " bytes");
        }
        try {
            return jsonMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new CollectorException(ErrorCode.UPSTREAM, "auth-service " + what + " returned malformed JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isEmpty() ? null : text;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record Response(int status, byte[] body) {
    }
}
