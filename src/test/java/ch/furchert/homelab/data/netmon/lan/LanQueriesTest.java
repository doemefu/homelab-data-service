package ch.furchert.homelab.data.netmon.lan;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The docs/060 §4.6 PromQL: instant selectors and the bucket guard, never a range over the bucket gauges. */
class LanQueriesTest {

    private static final Instant T = Instant.ofEpochSecond(1_758_620_700L);

    @Test
    void bucketQueriesAreGuardedInstantSelectors() {
        assertThat(LanQueries.ufwBlocks(T)).isEqualTo(
                "max by (node, src_ip, dport, proto) (homelab_ufw_blocks_bucket)"
                        + " and on (node) (homelab_netmon_bucket_end_timestamp_seconds == 1758620700)");
        assertThat(LanQueries.sshAuth(T)).isEqualTo(
                "max by (node, src_ip, outcome) (homelab_sshd_auth_bucket)"
                        + " and on (node) (homelab_netmon_bucket_end_timestamp_seconds == 1758620700)");
    }

    @Test
    void bucketGaugesAreNeverReadThroughARangeSelector() {
        for (String query : new String[]{LanQueries.ufwBlocks(T), LanQueries.sshAuth(T), LanQueries.EXPECTED_NODES,
                LanQueries.BUCKET_ENDS}) {
            assertThat(query).doesNotContain("last_over_time").doesNotContain("[").doesNotContain("_over_time");
        }
    }

    @Test
    void connectionsUseThePeakOverTheWindow() {
        assertThat(LanQueries.CONNECTIONS)
                .isEqualTo("max by (node, dport, src_ip, state) (max_over_time(homelab_lan_connections[15m]))");
    }

    @Test
    void nodeDiscoveryUsesTheScriptGauges() {
        assertThat(LanQueries.EXPECTED_NODES).isEqualTo("max by (node) (homelab_netmon_last_success_timestamp_seconds)");
        assertThat(LanQueries.BUCKET_ENDS).isEqualTo("max by (node) (homelab_netmon_bucket_end_timestamp_seconds)");
    }

    @Test
    void windowsAlignToQuarterHoursUtc() {
        assertThat(LanCollector.alignDown(Instant.parse("2026-09-24T10:14:59Z"))).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
        assertThat(LanCollector.alignDown(Instant.parse("2026-09-24T10:15:00Z"))).isEqualTo(Instant.parse("2026-09-24T10:15:00Z"));
    }
}
