package ch.furchert.homelab.data;

import ch.furchert.homelab.data.config.RetentionProperties;
import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.support.MutableClock;
import ch.furchert.homelab.data.support.RawEventRows;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRepository;
import ch.furchert.homelab.data.webanalytics.rollup.WebAnalyticsRollupCollector;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;

/**
 * Shared context for every rollup integration test (one cached context): the collector enabled
 * ({@code netmon.collectors.webanalytics-rollup.enabled=true}, unlike the shipped default) and a spy on
 * {@link RollupRepository}. Tests build their own collector on a {@link MutableClock}.
 */
@TestPropertySource(properties = "netmon.collectors.webanalytics-rollup.enabled=true")
public abstract class AbstractRollupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    protected JdbcClient jdbc;
    @MockitoSpyBean
    protected RollupRepository rollupRepository;
    @Autowired
    protected CollectorStateRepository state;
    @Autowired
    protected CollectorRunner runner;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected WebAnalyticsProperties properties;
    @Autowired
    protected RetentionProperties retention;

    protected RawEventRows rows;

    @BeforeEach
    void cleanWebAnalytics() {
        WebAnalyticsTables.clear(jdbc);
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = :name")
                .param("name", WebAnalyticsRollupCollector.NAME).update();
        rows = new RawEventRows(jdbc);
    }

    protected WebAnalyticsRollupCollector collector(MutableClock clock) {
        return new WebAnalyticsRollupCollector(rollupRepository, state, runner, transactionManager, clock, properties,
                retention);
    }

    protected static MutableClock clockAt(Instant instant) {
        return new MutableClock(instant);
    }
}
