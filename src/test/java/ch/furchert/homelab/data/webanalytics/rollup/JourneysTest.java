package ch.furchert.homelab.data.webanalytics.rollup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.click;
import static ch.furchert.homelab.data.support.RollupEvents.engagement;
import static ch.furchert.homelab.data.support.RollupEvents.goal;
import static ch.furchert.homelab.data.support.RollupEvents.pageview;
import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/** Funnels, group paths, transitions and seen-before (docs/100 §7.8, §8.5, rev 3.5). */
class JourneysTest {

    private static final int PRICES = 1 << 3;
    private final UUID v = UUID.randomUUID();
    private final Instant t0 = zurich("2026-11-08T10:00:00");

    @Test
    void fullOfferFunnelWithLateSnapshotsCountsAllSixSteps() {
        UUID auto = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        Visit visit = build(
                pageview(v, auto, t0, "/automation"),
                click(v, auto, t0.plusSeconds(60), "/automation", "cta", "automation-hero"),
                // The snapshot with "preise" is sent on navigation away, after the CTA click.
                engagement(v, auto, t0.plusSeconds(61), "/automation", 40_000, 75, false, PRICES, false),
                pageview(v, contact, t0.plusSeconds(62), "/contact"),
                goal(v, t0.plusSeconds(120)),
                // The form-start snapshot arrives on pagehide, after the goal.
                engagement(v, contact, t0.plusSeconds(125), "/contact", 50_000, 50, false, 0, true));
        assertThat(Journeys.offerSteps(visit)).isEqualTo(6);
        assertThat(Journeys.contactFormSteps(visit)).isEqualTo(3);
    }

    @Test
    void stepsMustOccurInOrder() {
        UUID contact = UUID.randomUUID();
        UUID auto = UUID.randomUUID();
        // Contact first (intent before prices), then the offer page with prices: F1 stops at "reached_prices".
        Visit visit = build(
                pageview(v, contact, t0, "/contact"),
                engagement(v, contact, t0.plusSeconds(30), "/contact", 20_000, 50, false, 0, true),
                pageview(v, auto, t0.plusSeconds(60), "/automation"),
                engagement(v, auto, t0.plusSeconds(90), "/automation", 20_000, 75, false, PRICES, false));
        assertThat(Journeys.offerSteps(visit)).isEqualTo(3);
        assertThat(Journeys.contactFormSteps(visit)).isEqualTo(2);
    }

    @Test
    void funnelStopsAtEachMissingStep() {
        assertThat(Journeys.offerSteps(build(pageview(v, UUID.randomUUID(), t0, "/about")))).isEqualTo(1);
        assertThat(Journeys.offerSteps(build(pageview(v, UUID.randomUUID(), t0, "/it")))).isEqualTo(2);
        UUID auto = UUID.randomUUID();
        assertThat(Journeys.offerSteps(build(pageview(v, auto, t0, "/automation"),
                engagement(v, auto, t0.plusSeconds(9), "/automation", 9_000, 75, false, PRICES, false))))
                .isEqualTo(3);
        assertThat(Journeys.offerSteps(build(pageview(v, auto, t0, "/automation"),
                engagement(v, auto, t0.plusSeconds(9), "/automation", 9_000, 75, false, PRICES, false),
                click(v, auto, t0.plusSeconds(10), "/automation", "mailto", null)))).isEqualTo(4);
        assertThat(Journeys.contactFormSteps(build(pageview(v, UUID.randomUUID(), t0, "/automation")))).isZero();
    }

    @Test
    void earliestMatchesAreUsed() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        // Prices reached on the second /automation view only; the earliest qualifying event decides each step.
        Visit visit = build(
                pageview(v, first, t0, "/automation"),
                pageview(v, second, t0.plusSeconds(120), "/automation"),
                engagement(v, second, t0.plusSeconds(150), "/automation", 20_000, 75, false, PRICES, false),
                click(v, first, t0.plusSeconds(60), "/automation", "cta", "automation-hero"),
                pageview(v, contact, t0.plusSeconds(180), "/contact"));
        assertThat(Journeys.offerSteps(visit)).isEqualTo(4); // intent at the /contact view, after prices
    }

    @Test
    void groupPathKeyCollapsesRepeatsAndTruncatesAfterFourGroups() {
        assertThat(Journeys.groupPathKey(build(pageview(v, UUID.randomUUID(), t0, "/"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(60), "/automation"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(120), "/it"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(180), "/contact"))))
                .isEqualTo("home > offer > contact > (exit)");
        assertThat(Journeys.groupPathKey(build(pageview(v, UUID.randomUUID(), t0, "/"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(60), "/automation"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(120), "/projects/home-lab"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(180), "/it"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(240), "/rowing"))))
                .isEqualTo("home > offer > proof > offer > … > (exit)");
        assertThat(Journeys.group("/imprint")).isEqualTo("other");
        assertThat(Journeys.group("/about")).isEqualTo("proof");
    }

    @Test
    void transitionsRunFromEntranceToExitWithoutSelfLoops() {
        Visit visit = build(pageview(v, UUID.randomUUID(), t0, "/"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(60), "/automation"),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(120), "/automation"), // reload after > 30 s
                pageview(v, UUID.randomUUID(), t0.plusSeconds(180), "/contact"));
        assertThat(Journeys.transitions(visit)).extracting(p -> p[0] + "->" + p[1])
                .containsExactly("(entrance)->/", "/->/automation", "/automation->/contact", "/contact->(exit)");
    }

    @Test
    void seenBeforeTheFirstIntent() {
        UUID auto = UUID.randomUUID();
        Visit visit = build(pageview(v, UUID.randomUUID(), t0, "/about"),
                pageview(v, auto, t0.plusSeconds(60), "/automation"),
                click(v, auto, t0.plusSeconds(90), "/automation", "cta", "automation-hero"),
                engagement(v, auto, t0.plusSeconds(91), "/automation", 30_000, 75, false, PRICES, false),
                pageview(v, UUID.randomUUID(), t0.plusSeconds(120), "/projects"));
        // Prices count (slot start before the click); /projects came after the intent.
        assertThat(Journeys.seenBeforeIntent(visit)).containsExactlyInAnyOrder(Journeys.Seen.ABOUT,
                Journeys.Seen.PRICES);
        assertThat(Journeys.seenBeforeIntent(build(pageview(v, UUID.randomUUID(), t0, "/contact"))))
                .containsExactly(Journeys.Seen.NONE);
        assertThat(Journeys.seenBeforeIntent(build(pageview(v, UUID.randomUUID(), t0, "/about")))).isEmpty();
    }

    private static Visit build(ch.furchert.homelab.data.webanalytics.rollup.RawEvent... events) {
        List<Visit> visits = VisitBuilder.build(List.of(events));
        assertThat(visits).hasSize(1);
        return visits.getFirst();
    }
}
