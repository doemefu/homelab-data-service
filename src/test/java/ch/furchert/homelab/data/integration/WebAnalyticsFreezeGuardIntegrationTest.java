package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractRollupIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.support.AggregateRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Freeze guard (docs/100 §7.5, WA-5; plan rulings R1, R9) against PostgreSQL: (d) keeps a FINAL day whose raw events are
 * gone (here an outdated-definition day the planner would otherwise recompute) and warns; a PROVISIONAL day is always
 * recomputed (an erased visitor's day goes to zero and is finalised); (b) never recomputes a day older than the raw
 * retention, even without a {@code rollup_day} row. Conditions (a)-(c) with a non-default retention: RollupPlannerTest.
 */
class WebAnalyticsFreezeGuardIntegrationTest extends AbstractRollupIntegrationTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-09");

    AggregateRows aggregates;

    @BeforeEach
    void setUp() {
        aggregates = new AggregateRows(jdbc);
    }

    @Test
    void aFinalDayWhoseRawEventsAreGoneKeepsItsAggregates() {
        LocalDate day = TODAY.minusDays(3);
        aggregates.rollupDay(day, true, 1); // outdated definition: a recompute candidate
        jdbc.sql("UPDATE webanalytics.rollup_day SET raw_events = 5 WHERE day = :d").param("d", day).update();
        aggregates.visits(day, "de", "desktop", 7, 0, 0, 0, 7);
        for (LocalDate d = day.plusDays(1); d.isBefore(TODAY); d = d.plusDays(1)) {
            aggregates.rollupDay(d, true);
        }

        assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                .isInstanceOf(CollectorWarning.class)
                .hasMessageContaining("freeze guard kept 1 day(s)");

        assertThat(jdbc.sql("SELECT sum(visits) FROM webanalytics.daily_visits WHERE day = :d").param("d", day)
                .query(Long.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT definition_version FROM webanalytics.rollup_day WHERE day = :d").param("d", day)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aProvisionalDayOfAnErasedVisitorIsRecomputedToZeroAndFinalised() throws Exception {
        LocalDate day = TODAY.minusDays(1);
        aggregates.rollupDay(TODAY.minusDays(2), true);
        aggregates.rollupDay(day, false);
        jdbc.sql("UPDATE webanalytics.rollup_day SET raw_events = 3 WHERE day = :d").param("d", day).update();
        aggregates.visits(day, "de", "desktop", 1, 0, 0, 0, 3);
        // The visitor's events were erased (DELETE /visitors/{id}): no raw events left for the day.

        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        assertThat(jdbc.sql("SELECT final FROM webanalytics.rollup_day WHERE day = :d").param("d", day)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT raw_events FROM webanalytics.rollup_day WHERE day = :d").param("d", day)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.daily_visits WHERE day = :d").param("d", day)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aDayOlderThanTheRetentionIsNeverRecomputedEvenWithoutARow() {
        LocalDate purged = TODAY.minusDays(397); // before today − (396 − 1)
        aggregates.rollupDay(TODAY.minusDays(400), true);
        aggregates.visits(purged, "de", "desktop", 9, 0, 0, 0, 9); // its rollup_day row was deleted ("forced")
        rows.landing(UUID.randomUUID(), zurich("2026-11-08T10:00:00"), "/", null, null);

        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.rollup_day WHERE day = :d").param("d", purged)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT sum(visits) FROM webanalytics.daily_visits WHERE day = :d").param("d", purged)
                .query(Long.class).single()).isEqualTo(9);
        // The gap scan starts at the retention bound (31 days per run from there on).
        assertThat(jdbc.sql("SELECT min(day) FROM webanalytics.rollup_day WHERE day > :d").param("d", purged)
                .query(LocalDate.class).single()).isEqualTo(TODAY.minusDays(395));
    }
}
