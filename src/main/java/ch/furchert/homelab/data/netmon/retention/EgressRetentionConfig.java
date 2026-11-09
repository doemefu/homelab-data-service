package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Retention targets for the NM-2 tables (docs/060 §3.3, §3.5, data-service#57). The hourly rows go after 8 days, but
 * only for UTC days that already have {@code egress_flow_daily} rows (guard), and before the daily target runs; the
 * daily rows go after 32 days.
 */
@Configuration(proxyBeanMethods = false)
public class EgressRetentionConfig {

    /** Fixed SQL on the row alias {@code t}: delete hourly rows only of UTC days that have daily rows. */
    static final String DAY_HAS_DAILY_ROWS = "EXISTS (SELECT 1 FROM netmon.egress_flow_daily d "
            + "WHERE d.day = (t.window_start AT TIME ZONE 'UTC')::date)";

    @Bean
    @Order(1)
    RetentionTarget egressFlowSnapshotsRetention(RetentionProperties properties) {
        return new RetentionTarget("egress_flow_snapshots", "window_start", properties.egressFlowSnapshotsDays(),
                DAY_HAS_DAILY_ROWS);
    }

    @Bean
    @Order(2)
    RetentionTarget egressFlowDailyRetention(RetentionProperties properties) {
        return new RetentionTarget("egress_flow_daily", "day", properties.egressFlowDailyDays());
    }
}
