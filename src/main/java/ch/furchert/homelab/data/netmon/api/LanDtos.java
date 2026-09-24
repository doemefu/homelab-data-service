package ch.furchert.homelab.data.netmon.api;

import java.time.Instant;
import java.util.List;

/** Response shapes of the NM-3 endpoints, field for field as docs/060 §7.2 defines them. */
public final class LanDtos {

    private LanDtos() {
    }

    public record LanConnections(List<ConnectionItem> items) {
    }

    /** {@code windows} = snapshot windows with this key; {@code lastSeen} = end of the newest one. */
    public record ConnectionItem(String node, int dport, String srcIp, String state, int peakConnections,
                                 long windows, Instant firstSeen, Instant lastSeen) {
    }

    /** {@code lowerBound} is always {@code true}: UFW logging is rate-limited (§5.4). */
    public record UfwBlocks(boolean lowerBound, UfwTotals totals, List<UfwBlockItem> items) {
    }

    public record UfwTotals(long blocks) {
    }

    public record UfwBlockItem(String srcIp, int dport, String proto, long blocks, List<String> nodes) {
    }

    public record SshAuth(List<SshAuthItem> items) {
    }

    public record SshAuthItem(String node, String srcIp, long accepted, long failed, long invalidUser) {
    }

    /** The {@code lan} block of {@code /ips/{ip}}; {@code sshFailed} counts {@code failed} + {@code invalid_user}. */
    public record IpLan(long ufwBlocks, long sshFailed) {
    }
}
