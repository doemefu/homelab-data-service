package ch.furchert.homelab.data.netmon.lan;

/** Validated rows of the three NM-3 tables for one {@code (window_start, node)} slice (docs/060 §3.3). */
final class LanRows {

    private LanRows() {
    }

    record Connection(String srcIp, int dport, String state, int peakConnections) {
    }

    record UfwBlock(String srcIp, int dport, String proto, int blocks) {
    }

    record SshAuth(String srcIp, String outcome, int attempts) {
    }
}
