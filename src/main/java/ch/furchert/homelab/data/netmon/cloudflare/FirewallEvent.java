package ch.furchert.homelab.data.netmon.cloudflare;

import java.time.Instant;

/** One {@code firewallEventsAdaptive} event (docs/060 §4.2 query B), normalised like {@link RequestGroup}. */
public record FirewallEvent(
        Instant occurredAt,
        String rayName,
        String clientIp,
        String country,
        Integer asn,
        String asnOrg,
        String action,
        String securitySource,
        String ruleId,
        String host,
        String method,
        String path,
        String userAgent) {
}
