package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retention targets for the NM-1 tables (docs/060 §3.3, §3.5). {@code blocklist_entries} holds the
 * current entries only and is not subject to retention.
 */
@Configuration(proxyBeanMethods = false)
public class InboundRetentionConfig {

    /**
     * §3.5 exception: the current entries of a list keep pointing at an old snapshot while the list stays
     * {@code unchanged}, so a snapshot still referenced by {@code blocklist_entries} is never deleted.
     */
    static final String SNAPSHOT_NOT_REFERENCED =
            "NOT EXISTS (SELECT 1 FROM netmon.blocklist_entries e WHERE e.snapshot_id = t.id)";

    @Bean
    RetentionTarget inboundRequestGroupsRetention(RetentionProperties properties) {
        return new RetentionTarget("inbound_request_groups", "window_start", properties.inboundRequestGroupsDays());
    }

    @Bean
    RetentionTarget firewallEventsRetention(RetentionProperties properties) {
        return new RetentionTarget("firewall_events", "occurred_at", properties.firewallEventsDays());
    }

    @Bean
    RetentionTarget ipEnrichmentRetention(RetentionProperties properties) {
        return new RetentionTarget("ip_enrichment", "last_seen", properties.ipEnrichmentDays());
    }

    @Bean
    RetentionTarget blocklistSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("blocklist_snapshots", "fetched_at", properties.blocklistSnapshotsDays(),
                SNAPSHOT_NOT_REFERENCED);
    }
}
