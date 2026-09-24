package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retention target for the NM-4 table (docs/060 §3.3, §3.5): 180 days on {@code occurred_at}. */
@Configuration(proxyBeanMethods = false)
public class LoginRetentionConfig {

    @Bean
    RetentionTarget loginEventsRetention(RetentionProperties properties) {
        return new RetentionTarget("login_events", "occurred_at", properties.loginEventsDays());
    }
}
