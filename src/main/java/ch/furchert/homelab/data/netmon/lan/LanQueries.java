package ch.furchert.homelab.data.netmon.lan;

import java.time.Instant;

/**
 * The PromQL of docs/060 §4.6 for the {@code lan} collector. The two bucket queries are <b>instant vector
 * selectors</b> guarded by {@code homelab_netmon_bucket_end_timestamp_seconds == T}: a range selector such as
 * {@code last_over_time(...[5m])} ignores staleness markers and could return the previous bucket's value,
 * which the guard would not filter out, so adjacent windows would double-count.
 */
final class LanQueries {

    /** Nodes that run the script ("expected nodes"), evaluated at the bucket evaluation time. */
    static final String EXPECTED_NODES = "max by (node) (homelab_netmon_last_success_timestamp_seconds)";

    /** The bucket each node currently publishes, evaluated at the bucket evaluation time. */
    static final String BUCKET_ENDS = "max by (node) (homelab_netmon_bucket_end_timestamp_seconds)";

    /** Peak concurrency per window; evaluated at {@code time = T} for {@code [T-15m, T)}. */
    static final String CONNECTIONS =
            "max by (node, dport, src_ip, state) (max_over_time(homelab_lan_connections[15m]))";

    private LanQueries() {
    }

    /** {@code ufw_block_snapshots} for the bucket ending at {@code windowEnd}. */
    static String ufwBlocks(Instant windowEnd) {
        return "max by (node, src_ip, dport, proto) (homelab_ufw_blocks_bucket)"
                + guard(windowEnd);
    }

    /** {@code ssh_auth_snapshots} for the bucket ending at {@code windowEnd}. */
    static String sshAuth(Instant windowEnd) {
        return "max by (node, src_ip, outcome) (homelab_sshd_auth_bucket)"
                + guard(windowEnd);
    }

    private static String guard(Instant windowEnd) {
        return " and on (node) (homelab_netmon_bucket_end_timestamp_seconds == " + windowEnd.getEpochSecond() + ")";
    }
}
