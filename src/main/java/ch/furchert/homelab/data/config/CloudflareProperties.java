package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.cloudflare.*} (docs/060 §4.2, §9). The token and zone id come from Secret
 * {@code data-service-secrets} via {@code CLOUDFLARE_API_TOKEN}/{@code CLOUDFLARE_ZONE_ID}; when either
 * is blank the Cloudflare collectors fail every run with {@code credentials} instead of calling out.
 *
 * @param graphqlUrl       GraphQL endpoint
 * @param apiToken         Analytics:Read token; never logged
 * @param zoneId           zone tag of furchert.ch
 * @param groupsPageSize   {@code limit} for query A; min(maxPageSize, 5000) per §4.2
 * @param firewallPageSize {@code limit} for query B; min(maxPageSize, 1000) per §4.2
 * @param firewallMaxPages page cap per firewall run (§4.2: 20)
 * @param maxQueriesPerRun catch-up throttle for the request-groups collector (§4.2: 60 per 5-min run)
 */
@ConfigurationProperties("netmon.cloudflare")
public record CloudflareProperties(
        @DefaultValue("https://api.cloudflare.com/client/v4/graphql") String graphqlUrl,
        @DefaultValue("") String apiToken,
        @DefaultValue("") String zoneId,
        @DefaultValue("5000") int groupsPageSize,
        @DefaultValue("1000") int firewallPageSize,
        @DefaultValue("20") int firewallMaxPages,
        @DefaultValue("60") int maxQueriesPerRun) {

    public CloudflareProperties {
        if (groupsPageSize < 1 || firewallPageSize < 1 || firewallMaxPages < 1 || maxQueriesPerRun < 2) {
            throw new IllegalArgumentException("netmon.cloudflare: page sizes and caps must be positive");
        }
    }

    public boolean hasCredentials() {
        return apiToken != null && !apiToken.isBlank() && zoneId != null && !zoneId.isBlank();
    }

    /** Never print the token. */
    @Override
    public String toString() {
        return "CloudflareProperties[graphqlUrl=" + graphqlUrl + ", credentials=" + (hasCredentials() ? "set" : "unset") + "]";
    }
}
