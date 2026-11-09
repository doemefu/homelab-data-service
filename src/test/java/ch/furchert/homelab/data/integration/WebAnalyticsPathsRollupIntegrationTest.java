package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractRollupIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.InboundRows;
import ch.furchert.homelab.data.support.MutableClock;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** WA-3b rollup against PostgreSQL (docs/100 §7.5, §7.8, §8.5, rev 3.5): journeys, submits, definition bump, CF baseline. */
class WebAnalyticsPathsRollupIntegrationTest extends AbstractRollupIntegrationTest {

    private static final int PRICES = 1 << 3;

    @Autowired
    DataSource dataSource;

    InboundRows inbound;

    @BeforeEach
    void cleanNetmon() {
        inbound = new InboundRows(jdbc);
        inbound.clear();
    }

    @Test
    void journeyTablesAreWrittenAndConsistentWithTheVisitTables() {
        UUID converter = UUID.randomUUID();
        UUID auto = rows.landing(converter, zurich("2026-11-08T10:00:00"), "/automation", "google.com", null);
        rows.click(converter, auto, 1, zurich("2026-11-08T10:01:00"), "/automation", "cta", "automation-hero");
        rows.engagement(converter, auto, 2, zurich("2026-11-08T10:01:01"), "/automation", 40_000, PRICES);
        UUID contact = rows.pageview(converter, zurich("2026-11-08T10:01:05"), "/contact");
        rows.goal(converter, zurich("2026-11-08T10:03:00"));
        rows.formStart(converter, contact, 1, zurich("2026-11-08T10:03:05")); // sent on pagehide, after the goal
        UUID reader = UUID.randomUUID();
        rows.landing(reader, zurich("2026-11-08T12:00:00"), "/", null, null);
        rows.pageview(reader, zurich("2026-11-08T12:01:00"), "/about");
        UUID bouncer = UUID.randomUUID();
        rows.landing(bouncer, zurich("2026-11-08T14:00:00"), "/it", null, null);

        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        long visits = sum("SELECT sum(visits) FROM webanalytics.daily_visits WHERE day = '2026-11-08'");
        assertThat(visits).isEqualTo(3);
        assertThat(funnel("offer", 1)).isEqualTo(visits);
        assertThat(funnel("offer", 2))
                .isEqualTo(sum("SELECT sum(offer_visits) FROM webanalytics.daily_visits WHERE day = '2026-11-08'"));
        assertThat(funnel("offer", 6)).isEqualTo(1)
                .isLessThanOrEqualTo(sum("SELECT sum(converting_visits) FROM webanalytics.daily_visits WHERE day = '2026-11-08'"));
        assertThat(funnel("contact_form", 1)).isEqualTo(1);
        assertThat(sum("SELECT sum(transitions) FROM webanalytics.daily_transition WHERE day = '2026-11-08' AND from_path = '(entrance)'"))
                .isEqualTo(visits);
        assertThat(sum("SELECT sum(transitions) FROM webanalytics.daily_transition WHERE day = '2026-11-08' AND to_path = '(exit)'"))
                .isEqualTo(visits);
        assertThat(sum("SELECT sum(visits) FROM webanalytics.daily_group_path WHERE day = '2026-11-08'")).isEqualTo(visits);
        assertThat(sum("SELECT sum(visits) FROM webanalytics.daily_entry_exit WHERE day = '2026-11-08'")).isEqualTo(visits);
        assertThat(jdbc.sql("SELECT dimension, sum(visits) FROM webanalytics.daily_dimension WHERE day = '2026-11-08' GROUP BY dimension")
                .query((rs, i) -> rs.getLong(2)).list()).hasSize(5).allMatch(n -> n == visits);
        long engaged = sum("SELECT sum(engaged_visits) FROM webanalytics.daily_visits WHERE day = '2026-11-08'");
        assertThat(engaged).isPositive();
        assertThat(jdbc.sql("SELECT dimension, sum(engaged_visits) FROM webanalytics.daily_dimension WHERE day = '2026-11-08' GROUP BY dimension")
                .query((rs, i) -> rs.getLong(2)).list()).hasSize(5).allMatch(n -> n == engaged);
        assertThat(jdbc.sql("SELECT visits FROM webanalytics.daily_group_path WHERE day = '2026-11-08' AND path_key = 'offer > contact > (exit)'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT visits FROM webanalytics.daily_intent_seen WHERE day = '2026-11-08' AND seen = 'prices'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void submitsCountOnTheSubmissionDayWhileTheConversionStaysOnTheVisitDay() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-07T23:50:00"), "/contact", null, null);
        rows.goal(visitor, zurich("2026-11-08T00:10:00"));
        new AggregateRows(jdbc).rollupDay(LocalDate.parse("2026-11-06"), true);
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect(); // 2026-11-07 and 2026-11-08

        assertThat(submits("2026-11-07")).isZero();
        assertThat(submits("2026-11-08")).isEqualTo(1);
        assertThat(sum("SELECT coalesce(sum(converting_visits), 0) FROM webanalytics.daily_visits WHERE day = '2026-11-07'"))
                .isEqualTo(1);
    }

    @Test
    void definitionBumpRecomputesDaysWithin30DaysOnly() {
        AggregateRows aggregates = new AggregateRows(jdbc);
        aggregates.rollupDay(LocalDate.parse("2026-10-09"), true, 1);
        for (LocalDate d = LocalDate.parse("2026-10-10"); d.isBefore(LocalDate.parse("2026-11-09")); d = d.plusDays(1)) {
            aggregates.rollupDay(d, true, 1);
        }
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/", null, null);

        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        assertThat(jdbc.sql("SELECT definition_version FROM webanalytics.rollup_day WHERE day = '2026-11-08'")
                .query(Integer.class).single()).isEqualTo(RollupPlanner.DEFINITION_VERSION);
        assertThat(jdbc.sql("SELECT definition_version FROM webanalytics.rollup_day WHERE day = '2026-10-10'")
                .query(Integer.class).single()).isEqualTo(RollupPlanner.DEFINITION_VERSION);
        assertThat(jdbc.sql("SELECT definition_version FROM webanalytics.rollup_day WHERE day = '2026-10-09'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(sum("SELECT count(*) FROM webanalytics.daily_transition WHERE day = '2026-11-08'")).isPositive();
    }

    @Test
    void oneVisitorWithOfferVisitsOnTwoDaysIsOneRepeatOfferViewer() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-02T10:00:00"), "/automation", null, null);
        rows.landing(visitor, zurich("2026-11-06T10:00:00"), "/it", null, null);
        MutableClock clock = clockAt(zurich("2026-11-03T07:30:00"));
        collector(clock).collect();
        clock.set(zurich("2026-11-09T07:30:00"));
        collector(clock).collect();
        assertThat(jdbc.sql("SELECT repeat_offer_viewers_28d FROM webanalytics.daily_visitors WHERE day = '2026-11-08'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void visitorTypeIsReturningWithAnEvent300DaysBack() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-08T10:00:00").minus(Duration.ofDays(300)), "/", null, null);
        rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/", null, null);
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();
        assertThat(jdbc.sql("SELECT value FROM webanalytics.daily_dimension WHERE day = '2026-11-08' AND dimension = 'visitor_type'")
                .query(String.class).list()).containsExactly("returning");
    }

    @Test
    void cfBaselineBackfillsEveryMissingDayFromTheSecondNetmonDayWithNormalisedPaths() {
        inbound.page(zurich("2026-11-05T22:00:00"), "/de/automation", 9); // oldest netmon day: partial, skipped
        Instant h = zurich("2026-11-06T10:00:00");
        inbound.page(h, "/de", 3);
        inbound.page(h, "/de/", 2);                                        // same row as /de
        inbound.page(h, "/en/automation/", 4);
        inbound.hour(h, "furchert.ch", "GET", "/de/projects/home-lab", 200, 5, 2.0); // sampled
        inbound.page(h, "/de/dashboard", 7);                               // not a tracked page
        inbound.page(h, "/de/imprint", 7);
        inbound.page(h, "/en/privacy", 7);
        inbound.page(h, "/automation", 7);                                 // no locale prefix
        inbound.page(h, "/de/a/b/c", 7);                                   // three segments
        inbound.hour(h, "furchert.ch", "POST", "/de/contact", 200, 7, 1.0);
        inbound.hour(h, "furchert.ch", "GET", "/de/contact", 404, 7, 1.0);
        inbound.hour(h, "auth.furchert.ch", "GET", "/de", 200, 7, 1.0);
        inbound.page(zurich("2026-11-08T09:00:00"), "/en", 1);

        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        assertThat(baseline()).containsExactlyInAnyOrder(
                "2026-11-06 / de 5 false", "2026-11-06 /automation en 4 false",
                "2026-11-06 /projects/home-lab de 5 true", "2026-11-08 / en 1 false");

        // A filled day is not backfilled again: a late netmon row for a day that is not rolled again changes nothing.
        inbound.page(zurich("2026-11-06T11:00:00"), "/de", 100);
        collector(clockAt(zurich("2026-11-09T07:35:00"))).collect();
        assertThat(baseline()).contains("2026-11-06 / de 5 false");
    }

    @Test
    void nightlyReplaceMatchesTheBackfillAndKeepsDaysNetmonNoLongerHolds() {
        inbound.page(zurich("2026-11-06T22:00:00"), "/de", 1);
        inbound.page(zurich("2026-11-08T09:00:00"), "/de/automation", 3);
        inbound.page(zurich("2026-11-08T23:30:00"), "/de/automation", 2);
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();
        List<String> backfilled = baseline();

        // Re-roll 2026-11-08 (gap): the nightly replace yields exactly the backfilled rows.
        jdbc.sql("DELETE FROM webanalytics.rollup_day WHERE day = '2026-11-08'").update();
        collector(clockAt(zurich("2026-11-09T07:40:00"))).collect();
        assertThat(baseline()).isEqualTo(backfilled).contains("2026-11-08 /automation de 5 false");

        // netmon lost the day (retention / outage): the stored baseline is kept, not wiped.
        inbound.clear();
        jdbc.sql("DELETE FROM webanalytics.rollup_day WHERE day = '2026-11-08'").update();
        collector(clockAt(zurich("2026-11-09T07:45:00"))).collect();
        assertThat(baseline()).contains("2026-11-08 /automation de 5 false");
    }

    @Test
    void emptyNetmonWritesNothingAndTheNextRunBackfills() {
        MutableClock clock = clockAt(zurich("2026-11-09T07:30:00"));
        collector(clock).collect();
        assertThat(baseline()).isEmpty();
        inbound.page(zurich("2026-11-06T10:00:00"), "/de", 1);
        inbound.page(zurich("2026-11-07T10:00:00"), "/de", 2);
        clock.advance(Duration.ofMinutes(5));
        collector(clock).collect();
        assertThat(baseline()).containsExactly("2026-11-07 / de 2 false");
    }

    @Test
    void dstDaysSumTwentyFiveAndTwentyThreeHourlyWindows() {
        inbound.page(zurich("2026-10-23T12:00:00"), "/de", 1); // oldest, partial
        for (Instant t = zurich("2026-10-25T00:00:00"); t.isBefore(zurich("2026-10-26T00:00:00")); t = t.plusSeconds(3600)) {
            inbound.page(t, "/de", 1);
        }
        for (Instant t = zurich("2027-03-28T00:00:00"); t.isBefore(zurich("2027-03-29T00:00:00")); t = t.plusSeconds(3600)) {
            inbound.page(t, "/en", 1);
        }
        collector(clockAt(zurich("2027-03-29T07:30:00"))).collect();
        assertThat(baseline()).contains("2026-10-25 / de 25 false", "2027-03-28 / en 23 false");
    }

    @Test
    void heldLockStopsTheBackfillWithAWarningButNeverAbortsTheRollup() throws Exception {
        new AggregateRows(jdbc).rollupDay(LocalDate.parse("2026-11-08"), true); // nothing left to roll
        inbound.page(zurich("2026-11-06T10:00:00"), "/de", 1);
        inbound.page(zurich("2026-11-07T10:00:00"), "/de", 1);
        try (Connection other = dataSource.getConnection(); Statement s = other.createStatement()) {
            s.execute("SELECT pg_advisory_lock(hashtext('webanalytics-rollup'))");
            try {
                assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                        .isInstanceOf(CollectorWarning.class)
                        .hasMessageContaining("CF baseline backfill stopped after 0 day(s)");
            } finally {
                s.execute("SELECT pg_advisory_unlock(hashtext('webanalytics-rollup'))");
            }
        }
        assertThat(baseline()).isEmpty();
    }

    @Test
    void aFailedBackfillDayDoesNotBlockTheOtherDaysAndIsResumedOnTheNextRun() {
        rows.landing(UUID.randomUUID(), zurich("2026-11-08T10:00:00"), "/", null, null); // a day is rolled
        inbound.page(zurich("2026-11-05T10:00:00"), "/de", 1); // oldest, partial
        inbound.page(zurich("2026-11-06T10:00:00"), "/de", 2);
        inbound.page(zurich("2026-11-07T10:00:00"), "/de", 3);
        inbound.page(zurich("2026-11-08T10:00:00"), "/de", 4);
        LocalDate slow = LocalDate.parse("2026-11-06");
        doThrow(new QueryTimeoutException("simulated")).doCallRealMethod()
                .when(rollupRepository).replaceCfBaseline(eq(slow), eq(slow), any(), any(), any());
        MutableClock clock = clockAt(zurich("2026-11-09T07:30:00"));

        // Each day is its own statement and transaction: one timed-out day leaves the others filled.
        assertThatThrownBy(() -> collector(clock).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("CF baseline backfill: 1 day(s) failed (QueryTimeoutException)");
        assertThat(sum("SELECT count(*) FROM webanalytics.rollup_day WHERE day = '2026-11-08'")).isEqualTo(1);
        assertThat(baseline()).containsExactly("2026-11-07 / de 3 false", "2026-11-08 / de 4 false");

        // The retry fills only the missing day; already filled days are skipped (a late row does not change them).
        inbound.page(zurich("2026-11-07T11:00:00"), "/de", 100);
        clock.advance(Duration.ofMinutes(5));
        collector(clock).collect();
        assertThat(baseline()).containsExactly("2026-11-06 / de 2 false", "2026-11-07 / de 3 false",
                "2026-11-08 / de 4 false");
    }

    @Test
    void aSystemicBackfillFailureStopsAfterThreeConsecutiveDays() {
        new AggregateRows(jdbc).rollupDay(LocalDate.parse("2026-11-08"), true); // nothing left to roll
        inbound.page(zurich("2026-11-01T10:00:00"), "/de", 1); // oldest, partial
        for (int d = 2; d <= 8; d++) {
            inbound.page(zurich("2026-11-0" + d + "T10:00:00"), "/de", d);
        }
        doThrow(new QueryTimeoutException("simulated"))
                .when(rollupRepository).replaceCfBaseline(any(), any(), any(), any(), any());

        // Seven missing days, but a fault that hits every day costs three statement timeouts, not seven.
        assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("CF baseline backfill: 3 day(s) failed (QueryTimeoutException)")
                .hasMessageContaining("remaining days deferred");
        verify(rollupRepository, times(3)).replaceCfBaseline(any(), any(), any(), any(), any());
        assertThat(baseline()).isEmpty();
    }

    @Test
    void onlyConsecutiveBackfillFailuresStopTheBackfill() {
        new AggregateRows(jdbc).rollupDay(LocalDate.parse("2026-11-08"), true); // nothing left to roll
        inbound.page(zurich("2026-11-01T10:00:00"), "/de", 1); // oldest, partial
        for (int d = 2; d <= 7; d++) {
            inbound.page(zurich("2026-11-0" + d + "T10:00:00"), "/de", d);
        }
        // fail, fail, OK, fail, fail, OK, OK (2026-11-08 has no netmon row and writes nothing)
        for (String failing : List.of("2026-11-02", "2026-11-03", "2026-11-05", "2026-11-06")) {
            LocalDate day = LocalDate.parse(failing);
            doThrow(new QueryTimeoutException("simulated"))
                    .when(rollupRepository).replaceCfBaseline(eq(day), eq(day), any(), any(), any());
        }

        assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("CF baseline backfill: 4 day(s) failed (QueryTimeoutException)")
                .hasMessageNotContaining("deferred");
        verify(rollupRepository, times(7)).replaceCfBaseline(any(), any(), any(), any(), any());
        assertThat(baseline()).containsExactly("2026-11-04 / de 4 false", "2026-11-07 / de 7 false");
    }

    @Test
    void theRefreshSkipsADayWhoseBackfillJustFailed() {
        rows.landing(UUID.randomUUID(), zurich("2026-11-08T10:00:00"), "/", null, null); // 2026-11-08 is rolled
        inbound.page(zurich("2026-11-06T10:00:00"), "/de", 1); // oldest, partial
        inbound.page(zurich("2026-11-07T10:00:00"), "/de", 2);
        inbound.page(zurich("2026-11-08T10:00:00"), "/de", 3);
        LocalDate slow = LocalDate.parse("2026-11-08");
        doThrow(new QueryTimeoutException("simulated"))
                .when(rollupRepository).replaceCfBaseline(eq(slow), eq(slow), any(), any(), any());

        assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("CF baseline backfill: 1 day(s) failed (QueryTimeoutException)")
                .hasMessageNotContaining("refresh");
        verify(rollupRepository, times(1)).replaceCfBaseline(eq(slow), eq(slow), any(), any(), any());
        assertThat(baseline()).containsExactly("2026-11-07 / de 2 false");
    }

    @Test
    void theRefreshStopsAfterThreeConsecutiveFailures() {
        AggregateRows aggregates = new AggregateRows(jdbc);
        aggregates.rollupDay(LocalDate.parse("2026-11-02"), true);
        aggregates.rollupDay(LocalDate.parse("2026-11-08"), true); // gap 2026-11-03..07 is rolled
        inbound.page(zurich("2026-11-01T10:00:00"), "/de", 1); // oldest, partial
        for (int d = 2; d <= 8; d++) {
            LocalDate day = LocalDate.parse("2026-11-0" + d);
            inbound.page(zurich("2026-11-0" + d + "T10:00:00"), "/de", d);
            aggregates.cfBaseline(day, "/", "de", d, false); // nothing for the backfill to do
        }
        doThrow(new QueryTimeoutException("simulated"))
                .when(rollupRepository).replaceCfBaseline(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("CF baseline refresh: 3 day(s) failed (QueryTimeoutException), remaining days deferred");
        verify(rollupRepository, times(3)).replaceCfBaseline(any(), any(), any(), any(), any());
        assertThat(sum("SELECT count(*) FROM webanalytics.rollup_day WHERE day BETWEEN '2026-11-03' AND '2026-11-07'"))
                .isEqualTo(5);
    }

    @Test
    void aFailedBackfillScanStillRefreshesTheRolledDaysAndTheNextRunFillsTheRest() {
        rows.landing(UUID.randomUUID(), zurich("2026-11-08T10:00:00"), "/", null, null); // a day is rolled
        inbound.page(zurich("2026-11-06T10:00:00"), "/de", 1); // oldest, partial
        inbound.page(zurich("2026-11-07T10:00:00"), "/de", 2);
        inbound.page(zurich("2026-11-08T10:00:00"), "/de", 3);
        doThrow(new DataAccessResourceFailureException("simulated")).doCallRealMethod()
                .when(rollupRepository).firstNetmonHour();
        MutableClock clock = clockAt(zurich("2026-11-09T07:30:00"));

        assertThatThrownBy(() -> collector(clock).collect())
                .isInstanceOf(CollectorWarning.class).hasMessageContaining("CF baseline backfill failed");
        assertThat(baseline()).containsExactly("2026-11-08 / de 3 false");

        clock.advance(Duration.ofMinutes(5));
        collector(clock).collect();
        assertThat(baseline()).containsExactly("2026-11-07 / de 2 false", "2026-11-08 / de 3 false");
    }

    private long funnel(String id, int step) {
        return sum("SELECT coalesce(sum(visits), 0) FROM webanalytics.daily_funnel WHERE day = '2026-11-08' AND funnel_id = '"
                + id + "' AND step = " + step);
    }

    private long submits(String day) {
        return jdbc.sql("SELECT consented_submits FROM webanalytics.daily_submits WHERE day = CAST(:day AS date)")
                .param("day", day).query(Long.class).optional().orElse(-1L);
    }

    private List<String> baseline() {
        return jdbc.sql("SELECT day, path, locale, requests, sampled FROM webanalytics.daily_cf_baseline ORDER BY day, path, locale")
                .query((rs, i) -> rs.getObject("day", LocalDate.class) + " " + rs.getString("path") + " "
                        + rs.getString("locale") + " " + rs.getLong("requests") + " " + rs.getBoolean("sampled"))
                .list();
    }

    private long sum(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }
}
