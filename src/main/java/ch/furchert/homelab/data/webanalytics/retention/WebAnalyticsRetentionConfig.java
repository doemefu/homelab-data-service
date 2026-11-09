package ch.furchert.homelab.data.webanalytics.retention;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.netmon.retention.RetentionTarget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retention of the raw web analytics events (docs/100 §7.7): {@code webanalytics.events} by {@code occurred_at} after
 * {@code webanalytics.retention.events-days} (396, at least 370), inside the existing 03:30 UTC retention job. Never
 * registered: the aggregates, {@code rollup_day}, {@code daily_ingest_stats} and {@code daily_enquiries}.
 */
@Configuration(proxyBeanMethods = false)
public class WebAnalyticsRetentionConfig {

    @Bean
    RetentionTarget webanalyticsEventsRetention(WebAnalyticsProperties properties) {
        return new RetentionTarget("events", "occurred_at", properties.retention().eventsDays(), null, "webanalytics",
                RetentionTarget.DEFAULT_BATCH_SIZE);
    }
}
