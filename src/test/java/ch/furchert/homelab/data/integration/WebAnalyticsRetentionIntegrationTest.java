package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.retention.RetentionJob;
import ch.furchert.homelab.data.netmon.retention.RetentionTarget;
import ch.furchert.homelab.data.support.RawEventRows;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raw-event retention (docs/100 §7.7): the registered {@code webanalytics.events} target purges rows older than 396
 * days inside the shared retention job and logs {@code [retention] webanalytics.events deleted=<n>}; the aggregates and
 * the ingest tables are never registered, and every netmon target keeps its schema.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebAnalyticsRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollectorRunner runner;
    @Autowired
    List<RetentionTarget> targets;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = 'retention'").update();
    }

    @Test
    void eventsOlderThan396DaysArePurgedAndLogged(CapturedOutput output) {
        RetentionTarget events = targets.stream().filter(t -> "webanalytics".equals(t.schema())).findFirst().orElseThrow();
        assertThat(events.table()).isEqualTo("events");
        assertThat(events.timeColumn()).isEqualTo("occurred_at");
        assertThat(events.days()).isEqualTo(396);

        RawEventRows raw = new RawEventRows(jdbc);
        UUID visitor = UUID.randomUUID();
        raw.pageview(visitor, Instant.now().minus(Duration.ofDays(397)), "/");
        raw.pageview(visitor, Instant.now().minus(Duration.ofDays(395)), "/it");
        new AggregateRowsProbe(jdbc).seed(LocalDate.now().minusDays(397));

        assertThat(runner.run(new RetentionJob(jdbc, List.of(events), runner))).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.rollup_day").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.daily_enquiries").query(Long.class).single()).isEqualTo(1);
        assertThat(output).contains("[retention] webanalytics.events deleted=1");
    }

    @Test
    void onlyTheEventsTableIsRegisteredAndNetmonTargetsKeepTheirSchema() {
        assertThat(targets.stream().filter(t -> "webanalytics".equals(t.schema())).map(RetentionTarget::table))
                .containsExactly("events");
        assertThat(targets.stream().filter(t -> !"webanalytics".equals(t.schema())))
                .isNotEmpty()
                .allSatisfy(t -> {
                    assertThat(t.schema()).isEqualTo("netmon");
                    assertThat(t.batchSize()).isEqualTo(RetentionTarget.DEFAULT_BATCH_SIZE);
                });
    }

    /** Old aggregate and ingest rows that retention must never touch. */
    private record AggregateRowsProbe(JdbcClient jdbc) {
        void seed(LocalDate day) {
            new ch.furchert.homelab.data.support.AggregateRows(jdbc).rollupDay(day, true);
            new ch.furchert.homelab.data.support.AggregateRows(jdbc).enquiries(day, 1);
        }
    }
}
