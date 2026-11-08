package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractRollupIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.webanalytics.rollup.WebAnalyticsRollupCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The two triggers (01:12, 07:30) go through one {@link ch.furchert.homelab.data.netmon.collector.CollectorRunner}
 * lock and both record success (docs/100 §7.5, §7.6; homelab#350 plan review). Uses the collector bean in a context
 * where it is enabled.
 */
class WebAnalyticsRollupTriggerIntegrationTest extends AbstractRollupIntegrationTest {

    @Autowired
    WebAnalyticsRollupCollector collector;
    @Autowired
    MockMvc mockMvc;

    @AfterEach
    void resetSpy() {
        reset(rollupRepository);
    }

    @Test
    void theTwoTriggersNeverOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(rollupRepository).rolledDays();

        CompletableFuture<Void> night = CompletableFuture.runAsync(collector::nightRun);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        Instant attemptOfNight = state.find(WebAnalyticsRollupCollector.NAME).map(CollectorState::lastAttemptAt)
                .orElseThrow();

        assertThat(runner.run(collector)).isFalse(); // the morning trigger finds the lock taken
        assertThat(state.find(WebAnalyticsRollupCollector.NAME).map(CollectorState::lastAttemptAt).orElseThrow())
                .isEqualTo(attemptOfNight);

        release.countDown();
        night.get(60, TimeUnit.SECONDS);
        assertThat(state.find(WebAnalyticsRollupCollector.NAME).map(CollectorState::lastSuccessAt)).isPresent();
    }

    @Test
    void bothTriggersRecordSuccessAndExportTheGauge() throws Exception {
        collector.nightRun();
        Instant afterNight = state.find(WebAnalyticsRollupCollector.NAME).map(CollectorState::lastSuccessAt)
                .orElseThrow();
        Thread.sleep(1_100); // the gauge has second resolution
        collector.morningRun();
        Instant afterMorning = state.find(WebAnalyticsRollupCollector.NAME).map(CollectorState::lastSuccessAt)
                .orElseThrow();
        assertThat(afterMorning).isAfter(afterNight);

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(scrape).contains("netmon_collector_last_success_timestamp_seconds{collector=\"webanalytics-rollup\"} "
                + (double) afterMorning.getEpochSecond());
    }
}
