package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.retention.<table>-days} (docs/060 §3.3, §3.5) for the NM-1 and NM-3 tables. Values below 1 are
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
        @DefaultValue("90") int sshAuthSnapshotsDays) {
}
