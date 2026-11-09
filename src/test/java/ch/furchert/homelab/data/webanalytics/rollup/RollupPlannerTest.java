package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner.RolledDay;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static ch.furchert.homelab.data.support.RollupEvents.ZURICH;
import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/** Day selection and the final/provisional close (docs/100 §7.5), with fixed instants. */
class RollupPlannerTest {

    private final ZurichDays days = new ZurichDays(ZURICH);
    private final RollupPlanner planner = new RollupPlanner(days);

    @Test
    void firstRunWritesYesterdayOnly() {
        assertThat(planner.plan(zurich("2026-11-09T01:12:00"), List.of())).containsExactly(LocalDate.parse("2026-11-08"));
        assertThat(planner.plan(zurich("2026-11-09T07:30:00"), List.of())).containsExactly(LocalDate.parse("2026-11-08"));
    }

    @Test
    void nightRunWritesYesterdayProvisionalAndMorningRunFinalisesIt() {
        List<RolledDay> rolled = new ArrayList<>(List.of(rolled("2026-11-07", true)));
        Instant night = zurich("2026-11-09T01:12:00");
        assertThat(planner.plan(night, rolled)).containsExactly(LocalDate.parse("2026-11-08"));
        assertThat(days.isClosed(LocalDate.parse("2026-11-08"), night)).isFalse();

        rolled.add(rolled("2026-11-08", false));
        Instant morning = zurich("2026-11-09T07:30:00");
        assertThat(planner.plan(morning, rolled)).containsExactly(LocalDate.parse("2026-11-08"));
        assertThat(days.isClosed(LocalDate.parse("2026-11-08"), morning)).isTrue();
    }

    @Test
    void normalDayClosesAt0715() {
        LocalDate day = LocalDate.parse("2026-11-08");
        assertThat(days.close(day)).isEqualTo(zurich("2026-11-09T07:15:00"));
    }

    @Test
    void springForwardDayClosesAt0815AndTheNextNightFinalises() {
        // 2027-03-28 is the spring-forward day: 7 h 15 min of elapsed time after midnight is 08:15 CEST.
        LocalDate saturday = LocalDate.parse("2027-03-27");
        assertThat(days.close(saturday)).isEqualTo(zurich("2027-03-28T08:15:00"));
        List<RolledDay> rolled = List.of(rolled("2027-03-26", true), rolled("2027-03-27", false));

        Instant morning = zurich("2027-03-28T07:30:00");
        assertThat(planner.plan(morning, rolled)).isEmpty(); // still provisional after the 07:30 run

        Instant nextNight = zurich("2027-03-29T01:12:00");
        // Order: gap-scan days first (2027-03-28, missing), then closed non-final days (2027-03-27).
        assertThat(planner.plan(nextNight, rolled)).containsExactly(LocalDate.parse("2027-03-28"),
                LocalDate.parse("2027-03-27"));
        assertThat(days.isClosed(saturday, nextNight)).isTrue();
    }

    @Test
    void fallBackDayClosesAt0615() {
        // 2026-10-25 is the fall-back day: 7 h 15 min of elapsed time after midnight is 06:15 CET.
        assertThat(days.close(LocalDate.parse("2026-10-24"))).isEqualTo(zurich("2026-10-25T06:15:00"));
    }

    @Test
    void skippedMorningRunIsMadeGoodByTheNextNight() {
        List<RolledDay> rolled = List.of(rolled("2026-11-07", true), rolled("2026-11-08", false));
        assertThat(planner.plan(zurich("2026-11-10T01:12:00"), rolled))
                .containsExactly(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-08"));
    }

    @Test
    void gapScanCatchesUpOldestFirstAtMost31Days() {
        List<RolledDay> rolled = List.of(rolled("2026-08-01", true));
        List<LocalDate> plan = planner.plan(zurich("2026-11-09T01:12:00"), rolled);
        assertThat(plan).hasSize(31).first().isEqualTo(LocalDate.parse("2026-08-02"));
        assertThat(plan).isSorted();
    }

    @Test
    void finalDaysAreNotRewritten() {
        List<RolledDay> rolled = List.of(rolled("2026-11-07", true), rolled("2026-11-08", true));
        assertThat(planner.plan(zurich("2026-11-09T07:30:00"), rolled)).isEmpty();
    }

    @Test
    void outdatedDefinitionIsBackfilledWithin30DaysNewestFirst() {
        List<RolledDay> rolled = new ArrayList<>();
        for (LocalDate d = LocalDate.parse("2026-09-01"); d.isBefore(LocalDate.parse("2026-11-09")); d = d.plusDays(1)) {
            rolled.add(new RolledDay(d, true, 0));
        }
        List<LocalDate> plan = planner.plan(zurich("2026-11-09T07:30:00"), rolled);
        assertThat(plan).first().isEqualTo(LocalDate.parse("2026-11-08"));
        assertThat(plan).last().isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(plan).hasSize(30);
    }

    @Test
    void daysOfTheFormerDefinitionAreRecomputedWithin30Days() {
        // WA-3b bumped the definition to 2: final days written with version 1 count as outdated.
        List<RolledDay> rolled = List.of(new RolledDay(LocalDate.parse("2026-11-08"), true, 1),
                new RolledDay(LocalDate.parse("2026-10-09"), true, 1),
                new RolledDay(LocalDate.parse("2026-10-10"), true, 1));
        assertThat(RollupPlanner.DEFINITION_VERSION).isEqualTo(2);
        List<LocalDate> plan = planner.plan(zurich("2026-11-09T07:30:00"), rolled);
        assertThat(plan).contains(LocalDate.parse("2026-11-08"), LocalDate.parse("2026-10-10"))
                .doesNotContain(LocalDate.parse("2026-10-09"));
    }

    @Test
    void eligibilityRejectsTodayAndDaysBeforeTheFirstRolledDay() {
        Instant now = zurich("2026-11-09T07:30:00");
        LocalDate first = LocalDate.parse("2026-11-01");
        assertThat(planner.eligible(LocalDate.parse("2026-11-09"), now, first, null)).isFalse();
        assertThat(planner.eligible(LocalDate.parse("2026-10-31"), now, first, null)).isFalse();
        assertThat(planner.eligible(LocalDate.parse("2026-11-01"), now, first, null)).isTrue();
    }

    @Test
    void freezeGuardBoundsTheRetentionFromTheConfiguredValue() {
        // (b) day >= today - (retentionDays - 1), here with a non-default retention of 370 days.
        RollupPlanner short370 = new RollupPlanner(days, 370);
        Instant now = zurich("2026-11-09T07:30:00");
        LocalDate today = LocalDate.parse("2026-11-09");
        assertThat(short370.eligible(today.minusDays(369), now, null, null)).isTrue();
        assertThat(short370.eligible(today.minusDays(370), now, null, null)).isFalse();
        assertThat(planner.eligible(today.minusDays(370), now, null, null)).isTrue(); // default 396
    }

    @Test
    void freezeGuardRecomputesARolledDayOnlyWithin30Days() {
        // (c) day >= today - 30 or no rollup_day row; (a) day < today.
        Instant now = zurich("2026-11-09T07:30:00");
        LocalDate today = LocalDate.parse("2026-11-09");
        RolledDay old = new RolledDay(today.minusDays(31), false, RollupPlanner.DEFINITION_VERSION);
        RolledDay edge = new RolledDay(today.minusDays(30), false, RollupPlanner.DEFINITION_VERSION);
        assertThat(planner.eligible(old.day(), now, null, old)).isFalse();
        assertThat(planner.eligible(edge.day(), now, null, edge)).isTrue();
        assertThat(planner.eligible(today.minusDays(31), now, null, null)).isTrue(); // missing day
        assertThat(planner.eligible(today, now, null, null)).isFalse();
    }

    @Test
    void planDropsOldNonFinalAndPurgedDays() {
        RollupPlanner short370 = new RollupPlanner(days, 370);
        Instant now = zurich("2026-11-09T07:30:00");
        LocalDate today = LocalDate.parse("2026-11-09");
        List<RolledDay> rolled = List.of(
                new RolledDay(today.minusDays(372), true, RollupPlanner.DEFINITION_VERSION), // first rolled day
                new RolledDay(today.minusDays(40), false, RollupPlanner.DEFINITION_VERSION),
                new RolledDay(today.minusDays(2), false, RollupPlanner.DEFINITION_VERSION));
        List<LocalDate> plan = short370.plan(now, rolled);
        // Refused missing days do not use up the 31-day gap budget: the scan starts at the retention bound.
        assertThat(plan).doesNotContain(today.minusDays(371), today.minusDays(370), today.minusDays(40))
                .contains(today.minusDays(369), today.minusDays(339), today.minusDays(2))
                .doesNotContain(today.minusDays(338));
    }

    private static RolledDay rolled(String day, boolean fin) {
        return new RolledDay(LocalDate.parse(day), fin, RollupPlanner.DEFINITION_VERSION);
    }
}
