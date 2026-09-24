package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.abuseipdb.*} (docs/060 §4.5). The {@code reputation} collector is unavailable (never
 * runs, reported {@code enabled=false}) while {@code ABUSEIPDB_API_KEY} is blank.
 *
 * @param url         check endpoint
 * @param apiKey      API key; never logged
 * @param dailyBudget checks per UTC day (free tier allows 1 000)
 * @param perRun      checks per run
 */
@ConfigurationProperties("netmon.abuseipdb")
public record AbuseIpDbProperties(
        @DefaultValue("https://api.abuseipdb.com/api/v2/check") String url,
        @DefaultValue("") String apiKey,
        @DefaultValue("200") int dailyBudget,
        @DefaultValue("10") int perRun) {

    public AbuseIpDbProperties {
        if (dailyBudget < 0 || perRun < 0) {
            throw new IllegalArgumentException("netmon.abuseipdb: budgets must not be negative");
        }
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never print the key. */
    @Override
    public String toString() {
        return "AbuseIpDbProperties[url=" + url + ", key=" + (hasKey() ? "set" : "unset") + "]";
    }
}
