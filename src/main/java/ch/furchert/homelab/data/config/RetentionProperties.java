package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.retention.<table>-days} (docs/060 §3.3, §3.5) for the NM-1, NM-3, NM-2 and NM-4 tables; the egress hourly
 * rows keep 8 days and their daily aggregate 32 days (data-service#57). Values below 1 are
 * rejected at startup by {@link ch.furchert.homelab.data.netmon.retention.RetentionTarget}.
 * {@code blocklist_entries} holds current entries only and is not subject to retention.
 */
@ConfigurationProperties("netmon.retention")
public record RetentionProperties(
        @DefaultValue("90") int inboundRequestGroupsDays,
        @DefaultValue("180") int firewallEventsDays,
        @DefaultValue("180") int ipEnrichmentDays,
        @DefaultValue("30") int blocklistSnapshotsDays,
        @DefaultValue("30") int lanConnectionSnapshotsDays,
        @DefaultValue("90") int ufwBlockSnapshotsDays,
        @DefaultValue("90") int sshAuthSnapshotsDays,
        @DefaultValue("8") int egressFlowSnapshotsDays,
        @DefaultValue("180") int loginEventsDays,
        @DefaultValue("32") int egressFlowDailyDays) {

    /**
     * data-service#57: the hourly egress rows need at least two days (the read cut-off is a whole UTC day), and the
     * daily rows must outlive them by at least two days, so every day the raw retention drops is still in the daily
     * table and covers a 30-day window.
     */
    public RetentionProperties {
        if (egressFlowSnapshotsDays < 2) {
            throw new IllegalArgumentException("netmon.retention.egress-flow-snapshots-days must be >= 2, was "
                    + egressFlowSnapshotsDays);
        }
        if (egressFlowDailyDays < egressFlowSnapshotsDays + 2) {
            throw new IllegalArgumentException("netmon.retention.egress-flow-daily-days must be >= "
                    + "egress-flow-snapshots-days + 2, was " + egressFlowDailyDays + " with " + egressFlowSnapshotsDays);
        }
    }
}
