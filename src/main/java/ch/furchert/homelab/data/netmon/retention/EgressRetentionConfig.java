package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retention target for the NM-2 table (docs/060 §3.3, §3.5): 30 days on {@code window_start}. */
@Configuration(proxyBeanMethods = false)
public class EgressRetentionConfig {

    @Bean
    RetentionTarget egressFlowSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("egress_flow_snapshots", "window_start", properties.egressFlowSnapshotsDays());
    }
}
