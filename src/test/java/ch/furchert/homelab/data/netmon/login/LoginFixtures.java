package ch.furchert.homelab.data.netmon.login;

import java.util.List;

/** Synthetic auth-service outbox payloads in the shape of auth-service INTERFACES.md ("login-event pull"). */
public final class LoginFixtures {

    public static final String TOKEN_URL = "http://auth.test:8080/oauth2/token";
    public static final String BASE_URL = "http://auth.test:8080";
    public static final String EVENTS_URL = BASE_URL + "/api/v1/login-events";
    /** A synthetic HMAC (not derived from any key). */
    public static final String HMAC_A = "3fa9c1d2" + "0".repeat(56);
    public static final String HMAC_B = "b".repeat(64);

    private LoginFixtures() {
    }

    public static String tokenResponse(String accessToken, int expiresIn) {
        return """
                {"access_token":"%s","token_type":"Bearer","expires_in":%d,"scope":"login-events:read"}
                """.formatted(accessToken, expiresIn);
    }

    public static String page(List<String> events, long nextAfter, boolean hasMore) {
        return """
                {"events":[%s],"nextAfter":%d,"hasMore":%s}
                """.formatted(String.join(",", events), nextAfter, hasMore);
    }

    /** One event; {@code clientIp}, {@code subject} and {@code userAgent} may be {@code null}. */
    public static String event(long id, String occurredAt, String outcome, String clientIp, String ipSource,
                               String hmac, String subject, String userAgent) {
        return """
                {"id":%d,"eventId":"0b6f1e2a-1111-4c1d-9a0e-%012d","occurredAt":"%s","outcome":"%s","clientIp":%s,
                 "ipSource":"%s","usernameHmac":"%s","subject":%s,"userAgent":%s}
                """.formatted(id, id, occurredAt, outcome, quoted(clientIp), ipSource, hmac, quoted(subject),
                quoted(userAgent));
    }

    public static String eventId(long id) {
        return "0b6f1e2a-1111-4c1d-9a0e-%012d".formatted(id);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
