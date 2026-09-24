package ch.furchert.homelab.data.netmon.api;

import java.time.Instant;
import java.util.List;

/** Response shapes of the NM-1 endpoints, field for field as docs/060 §7.2 defines them. */
public final class InboundDtos {

    private InboundDtos() {
    }

    public record InboundSummary(TimeWindow window, Totals totals, List<TopClientIp> topClientIps,
                                 List<CountryCount> topCountries, List<AsnCount> topAsns, List<HostCount> topHosts,
                                 List<PathCount> topPaths, List<StatusCount> statuses, List<TimelineBucket> timeline) {
    }

    public record Totals(long requests, long uniqueClientIps, boolean sampled) {
    }

    public record TopClientIp(String ip, long requests, String country, Integer asn, String asnOrg,
                              boolean blocklisted, Integer abuseScore, long firewallEvents) {
    }

    public record CountryCount(String country, long requests) {
    }

    public record AsnCount(Integer asn, String asnOrg, long requests) {
    }

    public record HostCount(String host, long requests) {
    }

    public record PathCount(String host, String path, long requests) {
    }

    public record StatusCount(int status, long requests) {
    }

    public record TimelineBucket(Instant bucketStart, long requests) {
    }

    public record FirewallEventItem(Instant occurredAt, String rayName, String clientIp, String country, Integer asn,
                                    String asnOrg, String action, String securitySource, String ruleId, String host,
                                    String method, String path, String userAgent, boolean blocklisted) {
    }

    public record FirewallEventPage(List<FirewallEventItem> items, String nextCursor) {
    }

    /** {@code logins} is {@code null} until NM-4 ships. */
    public record IpDetail(String ip, Instant firstSeen, Instant lastSeen, List<String> seenIn, String country,
                           Integer asn, String asnOrg, List<BlocklistHit> blocklists, AbuseIpDb abuseIpDb,
                           IpInbound inbound, List<FirewallEventItem> firewallEvents, Object logins,
                           LanDtos.IpLan lan) {
    }

    public record BlocklistHit(String list, String cidr, Instant fetchedAt) {
    }

    public record AbuseIpDb(Integer score, Integer reports, Instant checkedAt) {
    }

    public record IpInbound(long requests, List<HostCount> topHosts, List<PathCount> topPaths,
                            List<StatusCount> statuses) {
    }
}
