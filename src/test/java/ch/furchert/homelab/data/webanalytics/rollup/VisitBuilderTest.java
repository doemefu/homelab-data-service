package ch.furchert.homelab.data.webanalytics.rollup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.ZURICH;
import static ch.furchert.homelab.data.support.RollupEvents.click;
import static ch.furchert.homelab.data.support.RollupEvents.engagement;
import static ch.furchert.homelab.data.support.RollupEvents.goal;
import static ch.furchert.homelab.data.support.RollupEvents.pageview;
import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/** Visit rules of docs/100 §3.1, §3.4, §7.5 (rev 3.4 F8/F9/F19). */
class VisitBuilderTest {

    private final UUID visitor = UUID.randomUUID();
    private final Instant t0 = zurich("2026-11-08T10:00:00");

    @Test
    void gapOfMoreThan30MinutesStartsANewVisit() {
        List<Visit> visits = VisitBuilder.build(List.of(
                pageview(visitor, UUID.randomUUID(), t0, "/"),
                pageview(visitor, UUID.randomUUID(), t0.plus(Duration.ofMinutes(30)), "/it"),
                pageview(visitor, UUID.randomUUID(), t0.plus(Duration.ofMinutes(60)).plusSeconds(1), "/about")));
        assertThat(visits).hasSize(2);
        assertThat(visits.getFirst().slots()).hasSize(2);
    }

    @Test
    void landingWithReferrerOrUtmStartsANewVisit() {
        List<Visit> visits = VisitBuilder.build(List.of(
                pageview(visitor, UUID.randomUUID(), t0, "/", true, null, null),
                pageview(visitor, UUID.randomUUID(), t0.plusSeconds(60), "/it", true, "google.com", null),
                pageview(visitor, UUID.randomUUID(), t0.plusSeconds(120), "/about", true, null, "linkedin"),
                pageview(visitor, UUID.randomUUID(), t0.plusSeconds(180), "/contact", true, null, null)));
        assertThat(visits).hasSize(3);
        assertThat(visits.get(2).slots()).extracting(PageSlot::path).containsExactly("/about", "/contact");
    }

    @Test
    void visitIsCutAfterSixHours() {
        List<RawEvent> events = new ArrayList<>();
        for (int minute = 0; minute <= 6 * 60 + 20; minute += 20) {
            events.add(pageview(visitor, UUID.randomUUID(), t0.plus(Duration.ofMinutes(minute)), minute % 40 == 0 ? "/" : "/it"));
        }
        List<Visit> visits = VisitBuilder.build(events);
        assertThat(visits).hasSize(2);
        assertThat(Duration.between(visits.get(0).firstEventAt(), visits.get(1).firstEventAt()))
                .isGreaterThan(Duration.ofHours(6));
    }

    @Test
    void reloadOfTheSamePathWithin30SecondsIsMergedAndEngagementIsSummed() {
        UUID first = UUID.randomUUID();
        UUID reload = UUID.randomUUID();
        List<Visit> visits = VisitBuilder.build(List.of(
                pageview(visitor, first, t0, "/automation"),
                engagement(visitor, first, t0.plusSeconds(5), "/automation", 3_000, 25, false, 1, false),
                engagement(visitor, first, t0.plusSeconds(9), "/automation", 8_000, 50, false, 3, false),
                pageview(visitor, reload, t0.plusSeconds(30), "/automation"),
                engagement(visitor, reload, t0.plusSeconds(40), "/automation", 7_000, 25, false, 8, false)));
        Visit visit = visits.getFirst();
        assertThat(visit.slots()).hasSize(1);
        PageSlot slot = visit.slots().getFirst();
        assertThat(slot.pageviewIds()).containsExactly(first, reload);
        assertThat(slot.engagedMs()).isEqualTo(15_000); // MAX per original (8 s, 7 s), summed
        assertThat(slot.scrollMax()).isEqualTo(50);
        assertThat(slot.sectionMask()).isEqualTo(11);
        assertThat(visit.engaged()).isTrue(); // 15 s >= 10 s on a single page
    }

    @Test
    void reloadAfter31SecondsOrOfAnotherPathIsNotMerged() {
        assertThat(VisitBuilder.build(List.of(
                pageview(visitor, UUID.randomUUID(), t0, "/automation"),
                pageview(visitor, UUID.randomUUID(), t0.plusSeconds(31), "/automation"),
                pageview(visitor, UUID.randomUUID(), t0.plusSeconds(40), "/it"))).getFirst().slots()).hasSize(3);
    }

    @Test
    void engagedTimeIsTheMaxSnapshotNotTheSum() {
        UUID pv = UUID.randomUUID();
        Visit visit = VisitBuilder.build(List.of(
                pageview(visitor, pv, t0, "/automation"),
                engagement(visitor, pv, t0.plusSeconds(4), "/automation", 4_000, 0, false, 0, false),
                engagement(visitor, pv, t0.plusSeconds(8), "/automation", 8_000, 0, false, 0, false))).getFirst();
        assertThat(visit.engagedMs()).isEqualTo(8_000);
        assertThat(visit.engaged()).isFalse();
    }

    @Test
    void lateSnapshotOfAKnownPageviewJoinsItsVisitRegardlessOfTheGap() {
        UUID pv = UUID.randomUUID();
        List<Visit> visits = VisitBuilder.build(List.of(
                pageview(visitor, pv, t0, "/automation"),
                engagement(visitor, pv, t0.plus(Duration.ofMinutes(45)), "/automation", 120_000, 75, false, 0, false)));
        assertThat(visits).hasSize(1);
        assertThat(visits.getFirst().orphan()).isFalse();
        assertThat(visits.getFirst().engagedMs()).isEqualTo(120_000);
    }

    @Test
    void eventsWithoutALoadedPageviewFormAnOrphanVisit() {
        List<Visit> visits = VisitBuilder.build(List.of(
                engagement(visitor, UUID.randomUUID(), t0, "/automation", 1_000, 0, false, 0, false)));
        assertThat(visits).singleElement().satisfies(v -> assertThat(v.orphan()).isTrue());
    }

    @Test
    void visitWithMoreThan100PageviewsIsExcluded() {
        List<RawEvent> events = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            events.add(pageview(visitor, UUID.randomUUID(), t0.plusSeconds(i * 40L), i % 2 == 0 ? "/" : "/it"));
        }
        assertThat(VisitBuilder.build(events)).singleElement().satisfies(v -> assertThat(v.excluded()).isTrue());
        assertThat(VisitBuilder.build(events.subList(0, 100))).singleElement()
                .satisfies(v -> assertThat(v.excluded()).isFalse());
    }

    @Test
    void keyEventsMakeAVisitEngaged() {
        UUID pv = UUID.randomUUID();
        assertThat(VisitBuilder.build(List.of(pageview(visitor, pv, t0, "/automation"),
                click(visitor, pv, t0.plusSeconds(2), "/automation", "tel", null))).getFirst())
                .satisfies(v -> {
                    assertThat(v.contactIntent()).isTrue();
                    assertThat(v.engaged()).isTrue();
                });
        assertThat(VisitBuilder.build(List.of(pageview(visitor, UUID.randomUUID(), t0, "/contact"))).getFirst().engaged())
                .isTrue();
        assertThat(VisitBuilder.build(List.of(pageview(visitor, UUID.randomUUID(), t0, "/about"),
                goal(visitor, t0.plusSeconds(5)))).getFirst().converting()).isTrue();
        assertThat(VisitBuilder.build(List.of(pageview(visitor, UUID.randomUUID(), t0, "/about"))).getFirst().engaged())
                .isFalse();
    }

    @Test
    void visitsAreAttributedToTheZurichDayOfTheirFirstPageview() {
        ZurichDays days = new ZurichDays(ZURICH);
        // Fall-back day: 2026-10-25 23:30 CET is 22:30 UTC.
        Instant lateFallBack = zurich("2026-10-25T23:30:00");
        assertThat(lateFallBack).isEqualTo(Instant.parse("2026-10-25T22:30:00Z"));
        assertThat(days.dayOf(VisitBuilder.build(List.of(pageview(visitor, UUID.randomUUID(), lateFallBack, "/")))
                .getFirst().attributedAt())).isEqualTo(LocalDate.parse("2026-10-25"));
        // Spring-forward day: 2026-03-29 00:30 is still CET (the switch is at 02:00), 23:30 UTC the day before.
        Instant earlySpring = zurich("2026-03-29T00:30:00");
        assertThat(earlySpring).isEqualTo(Instant.parse("2026-03-28T23:30:00Z"));
        assertThat(days.dayOf(earlySpring)).isEqualTo(LocalDate.parse("2026-03-29"));
        // A visit crossing midnight counts on its start day.
        Instant beforeMidnight = zurich("2026-11-08T23:50:00");
        List<Visit> crossing = VisitBuilder.build(List.of(
                pageview(visitor, UUID.randomUUID(), beforeMidnight, "/"),
                pageview(visitor, UUID.randomUUID(), beforeMidnight.plus(Duration.ofMinutes(20)), "/it")));
        assertThat(crossing).singleElement()
                .satisfies(v -> assertThat(days.dayOf(v.attributedAt())).isEqualTo(LocalDate.parse("2026-11-08")));
    }
}
