package ch.furchert.homelab.data.netmon.ip;

import java.net.InetAddress;
import java.util.List;

/**
 * The docs/060 §4.3 public-only rule. Addresses in these ranges are never enriched, never matched
 * against blocklists and never sent to AbuseIPDB; blocklist entries overlapping them are dropped (§4.4).
 */
public final class PublicIpFilter {

    public static final List<Cidr> NON_PUBLIC = List.of(
            Cidr.of("10.0.0.0/8"), Cidr.of("172.16.0.0/12"), Cidr.of("192.168.0.0/16"), Cidr.of("100.64.0.0/10"),
            Cidr.of("127.0.0.0/8"), Cidr.of("169.254.0.0/16"), Cidr.of("224.0.0.0/4"), Cidr.of("240.0.0.0/4"),
            Cidr.of("0.0.0.0/8"),
            Cidr.of("::1/128"), Cidr.of("fc00::/7"), Cidr.of("fe80::/10"));

    private PublicIpFilter() {
    }

    public static boolean isPublic(InetAddress address) {
        return NON_PUBLIC.stream().noneMatch(range -> range.contains(address));
    }

    public static boolean isPublic(String address) {
        return IpAddresses.parse(address).map(PublicIpFilter::isPublic).orElse(false);
    }

    /** True if any address of {@code cidr} is non-public. */
    public static boolean overlapsNonPublic(Cidr cidr) {
        return NON_PUBLIC.stream().anyMatch(range -> range.overlaps(cidr));
    }
}
