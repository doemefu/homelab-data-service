package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.auth-service.*} (docs/060 §7.6, §9): the login-event pull from auth-service. The client secret comes
 * from Secret {@code data-service-secrets} key {@code auth-client-secret} via {@code AUTH_CLIENT_SECRET}; while it is
 * blank the {@code login-events} collector fails every run with {@code credentials} instead of calling out.
 *
 * @param tokenUrl     OAuth2 token endpoint ({@code AUTH_TOKEN_URL})
 * @param url          auth-service base URL ({@code AUTH_SERVICE_URL}); the outbox is {@code <url>/api/v1/login-events}
 * @param clientId     the {@code data-service} client ({@code AUTH_CLIENT_ID})
 * @param clientSecret plaintext client secret; never logged
 * @param pageSize     {@code limit} per outbox page, 1..1000 (auth-service's maximum; default 500)
 * @param maxPages     page cap per run (§7.6: 10)
 */
@ConfigurationProperties("netmon.auth-service")
public record AuthServiceProperties(
        @DefaultValue("http://auth-service.apps.svc.cluster.local:8080/oauth2/token") String tokenUrl,
        @DefaultValue("http://auth-service.apps.svc.cluster.local:8080") String url,
        @DefaultValue("data-service") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("500") int pageSize,
        @DefaultValue("10") int maxPages) {

    /** auth-service rejects a larger {@code limit} with 400. */
    public static final int MAX_PAGE_SIZE = 1000;

    public AuthServiceProperties {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("netmon.auth-service.page-size must be between 1 and " + MAX_PAGE_SIZE
                    + ", was " + pageSize);
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("netmon.auth-service.max-pages must be >= 1, was " + maxPages);
        }
    }

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /** The outbox endpoint, {@code GET <url>/api/v1/login-events}. */
    public String loginEventsUrl() {
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return base + "/api/v1/login-events";
    }

    /** Never print the secret. */
    @Override
    public String toString() {
        return "AuthServiceProperties[tokenUrl=" + tokenUrl + ", url=" + url + ", clientId=" + clientId
                + ", credentials=" + (hasCredentials() ? "set" : "unset") + "]";
    }
}
