package ch.furchert.homelab.data.netmon.cloudflare;

/**
 * One {@code httpRequestsAdaptiveGroups} row (docs/060 §4.2 query A, no ASN dimensions), already normalised: {@code path}
 * truncated to 1024 chars, {@code country} null unless two characters, blank strings for missing
 * NOT NULL dimensions.
 */
public record RequestGroup(
        String clientIp,
        String country,
        String host,
        String method,
        String path,
        int status,
        long count,
        double sampleInterval) {
}
