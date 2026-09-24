package ch.furchert.homelab.data.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the collectors' {@code @Scheduled} triggers (docs/060 §4.1). {@code netmon.scheduling.enabled=false}
 * turns every trigger off; the test suite uses it so 5-minute crons never fire mid-test. Production
 * leaves it at the default {@code true}; single collectors are stopped with their kill switch instead.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "netmon.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
