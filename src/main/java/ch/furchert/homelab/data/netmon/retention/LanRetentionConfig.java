package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retention targets for the NM-3 tables (docs/060 §3.3, §3.5): 30 / 90 / 90 days on {@code window_start}. */
@Configuration(proxyBeanMethods = false)
public class LanRetentionConfig {

    @Bean
    RetentionTarget lanConnectionSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("lan_connection_snapshots", "window_start", properties.lanConnectionSnapshotsDays());
    }

    @Bean
    RetentionTarget ufwBlockSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("ufw_block_snapshots", "window_start", properties.ufwBlockSnapshotsDays());
    }

    @Bean
    RetentionTarget sshAuthSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("ssh_auth_snapshots", "window_start", properties.sshAuthSnapshotsDays());
    }
}
