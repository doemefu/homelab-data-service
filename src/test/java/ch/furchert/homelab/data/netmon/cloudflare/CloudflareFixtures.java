package ch.furchert.homelab.data.netmon.cloudflare;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloudflare GraphQL response fixtures shaped after docs/060 §4.2 queries A and B (the Free-plan field
 * probe is still pending, so these follow the documented field names; see the worklog).
 */
public final class CloudflareFixtures {

    public static final String URL = "https://cloudflare.test/client/v4/graphql";

    private CloudflareFixtures() {
    }

    /** One {@code httpRequestsAdaptiveGroups} row. */
    public static String group(String ip, String host, String method, String path, int status, long count,
                               double sampleInterval) {
        return """
                {"count":%d,"avg":{"sampleInterval":%s},"dimensions":{"clientIP":"%s","clientCountryName":"DE",
                 "clientRequestHTTPHost":"%s",
                 "clientRequestHTTPMethodName":"%s","clientRequestPath":"%s","edgeResponseStatus":%d}}"""
                .formatted(count, sampleInterval, ip, host, method, path, status);
    }

    public static String groupsResponse(List<String> rows) {
        return "{\"data\":{\"viewer\":{\"zones\":[{\"httpRequestsAdaptiveGroups\":[" + String.join(",", rows)
                + "]}]}},\"errors\":null}";
    }

    public static String groupsResponse(String... rows) {
        return groupsResponse(List.of(rows));
    }

    /** One {@code firewallEventsAdaptive} event. */
    public static String event(Instant at, String ray, String ip, String action, String ruleId) {
        return """
                {"datetime":"%s","rayName":"%s","clientIP":"%s","clientCountryName":"US","clientAsn":"14061",
                 "clientASNDescription":"DIGITALOCEAN-ASN","action":"%s","source":"firewallManaged","ruleId":%s,
                 "clientRequestHTTPHost":"furchert.ch","clientRequestHTTPMethodName":"GET",
                 "clientRequestPath":"/wp-login.php","userAgent":"curl/8.0"}"""
                .formatted(at, ray, ip, action, ruleId == null ? "null" : "\"" + ruleId + "\"");
    }

    public static String eventsResponse(List<String> events) {
        return "{\"data\":{\"viewer\":{\"zones\":[{\"firewallEventsAdaptive\":[" + String.join(",", events)
                + "]}]}},\"errors\":null}";
    }

    /** {@code count} events one second apart starting at {@code start}, distinct ray ids. */
    public static List<String> events(Instant start, int count, String rayPrefix) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(event(start.plusSeconds(i), rayPrefix + i, "198.51.100." + (i % 200 + 1), "block", "rule-1"));
        }
        return events;
    }

    public static String errorsResponse(String message) {
        return "{\"data\":null,\"errors\":[{\"message\":\"" + message + "\",\"path\":[\"viewer\"]}]}";
    }

}
