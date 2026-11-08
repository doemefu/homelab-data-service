package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DayRows;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.PageRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.click;
import static ch.furchert.homelab.data.support.RollupEvents.engagement;
import static ch.furchert.homelab.data.support.RollupEvents.pageview;
import static ch.furchert.homelab.data.support.RollupEvents.pageviewIn;
import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/** Row building of docs/100 §7.8. */
class DayAggregatorTest {

    private final Instant t0 = zurich("2026-11-08T10:00:00");

    @Test
    void visitViewingBothLocalesCountsOncePerPath() {
        UUID v = UUID.randomUUID();
        Visit visit = VisitBuilder.build(List.of(
                pageviewIn(v, UUID.randomUUID(), t0, "/automation", "de", "desktop"),
                pageviewIn(v, UUID.randomUUID(), t0.plusSeconds(60), "/automation", "en", "desktop"))).getFirst();
        DayRows rows = DayAggregator.aggregate(List.of(facts(visit)), List.of());
        PageRow page = rows.pages().getFirst();
        assertThat(rows.pages()).hasSize(1);
        assertThat(page.entryLocale()).isEqualTo("de");
        assertThat(page.pageviews()).isEqualTo(2);
        assertThat(page.pageviewsDe()).isEqualTo(1);
        assertThat(page.pageviewsEn()).isEqualTo(1);
        assertThat(page.visitsViewed()).isEqualTo(1);
        assertThat(page.entries()).isEqualTo(1);
        assertThat(page.exits()).isEqualTo(1);
    }

    @Test
    void histogramScrollAndDeepestSection() {
        UUID v = UUID.randomUUID();
        UUID pv = UUID.randomUUID();
        UUID fitting = UUID.randomUUID();
        Visit visit = VisitBuilder.build(List.of(
                pageview(v, fitting, t0, "/it"),
                engagement(v, fitting, t0.plusSeconds(3), "/it", 3_000, 100, true, 0, false),
                pageview(v, pv, t0.plusSeconds(60), "/automation"),
                engagement(v, pv, t0.plusSeconds(200), "/automation", 125_000, 75, false, 0b1001, false))).getFirst();
        DayRows rows = DayAggregator.aggregate(List.of(facts(visit)), List.of());
        PageRow automation = rows.pages().stream().filter(p -> p.path().equals("/automation")).findFirst().orElseThrow();
        assertThat(automation.histogram()[5]).isEqualTo(1); // 2-5 min
        assertThat(automation.scrollSamples()).isEqualTo(1);
        assertThat(automation.scroll()).containsExactly(1, 1, 1, 0, 0);
        PageRow it = rows.pages().stream().filter(p -> p.path().equals("/it")).findFirst().orElseThrow();
        assertThat(it.engagementSamples()).isEqualTo(1);
        assertThat(it.scrollSamples()).isZero(); // fits = true is excluded from scroll statistics
        assertThat(rows.sections()).extracting(RollupRows.SectionRow::section, RollupRows.SectionRow::reachedPageviews,
                        RollupRows.SectionRow::deepestAtExitVisits)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("hero", 1, 0),
                        org.assertj.core.groups.Tuple.tuple("preise", 1, 1));
    }

    @Test
    void outboundTargetsBeyondTheTop50AreFoldedBeforeCounting() {
        List<VisitFacts> visits = new ArrayList<>();
        Map<String, Integer> expectedKept = new HashMap<>();
        for (int i = 0; i < 52; i++) {
            UUID v = UUID.randomUUID();
            UUID pv = UUID.randomUUID();
            String target = String.format("site%02d.example", i);
            int repeat = i < 50 ? 2 : 1; // two visits each for the first 50 targets
            for (int r = 0; r < repeat; r++) {
                visits.add(facts(VisitBuilder.build(List.of(pageview(v, pv, t0, "/projects"),
                        click(v, pv, t0.plusSeconds(1), "/projects", "outbound", target))).getFirst()));
                v = UUID.randomUUID();
                pv = UUID.randomUUID();
            }
            if (i < 50) {
                expectedKept.put(target, 2);
            }
        }
        DayRows rows = DayAggregator.aggregate(visits, List.of());
        assertThat(rows.clicks()).hasSize(51);
        assertThat(rows.clicks()).filteredOn(c -> c.target().equals(DayAggregator.OTHER)).singleElement()
                .satisfies(c -> {
                    assertThat(c.clicks()).isEqualTo(2);
                    assertThat(c.visits()).isEqualTo(2);
                });
    }

    @Test
    void topNTieBreakIsByKey() {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            counts.put(String.format("k%02d", i), 1);
        }
        assertThat(DayAggregator.topN(counts)).hasSize(50).contains("k00", "k49").doesNotContain("k50");
    }

    @Test
    void excludedVisitsOnlyCountAsExcluded() {
        UUID v = UUID.randomUUID();
        Visit visit = VisitBuilder.build(List.of(pageview(v, UUID.randomUUID(), t0, "/"))).getFirst();
        DayRows rows = DayAggregator.aggregate(List.of(), List.of(visit));
        assertThat(rows.visits()).singleElement().satisfies(r -> {
            assertThat(r.visits()).isZero();
            assertThat(r.excludedVisits()).isEqualTo(1);
        });
        assertThat(rows.pages()).isEmpty();
    }

    @Test
    void transitionsFromEntranceAndToExitEachSumToTheVisits() {
        List<VisitFacts> visits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID v = UUID.randomUUID();
            List<RawEvent> events = new ArrayList<>(List.of(pageview(v, UUID.randomUUID(), t0, "/")));
            for (int p = 0; p < i; p++) {
                events.add(pageview(v, UUID.randomUUID(), t0.plusSeconds(60L * (p + 1)), p % 2 == 0 ? "/it" : "/"));
            }
            visits.add(facts(VisitBuilder.build(events).getFirst()));
        }
        DayRows rows = DayAggregator.aggregate(visits, List.of());
        assertThat(rows.transitions().stream().filter(t -> t.fromPath().equals("(entrance)"))
                .mapToInt(RollupRows.TransitionRow::transitions).sum()).isEqualTo(3);
        assertThat(rows.transitions().stream().filter(t -> t.toPath().equals("(exit)"))
                .mapToInt(RollupRows.TransitionRow::transitions).sum()).isEqualTo(3);
        assertThat(rows.funnels()).filteredOn(f -> f.funnelId().equals("offer") && f.step() == 1).singleElement()
                .satisfies(f -> assertThat(f.visits()).isEqualTo(3));
        assertThat(rows.entryExits().stream().mapToInt(RollupRows.EntryExitRow::visits).sum()).isEqualTo(3);
        assertThat(rows.groupPaths().stream().mapToInt(RollupRows.GroupPathRow::visits).sum()).isEqualTo(3);
    }

    @Test
    void dimensionsCountVisitsAndEngagedVisitsWithEmptyForMissingValues() {
        UUID v = UUID.randomUUID();
        RawEvent pv = new RawEvent(UUID.randomUUID(), RawEvent.PAGEVIEW, t0, v, UUID.randomUUID(), 0, "/", "de", true,
                null, null, null, null, "mobile", null, null, null, null, null, null, null, null, null, "safari",
                "ios", "<480");
        DayRows rows = DayAggregator.aggregate(List.of(new VisitFacts(VisitBuilder.build(List.of(pv)).getFirst(),
                false, "direct", "")), List.of());
        // A single landing pageview without engagement: not engaged.
        assertThat(rows.dimensions()).extracting(d -> d.dimension() + "=" + d.value() + ":" + d.visits() + "/"
                        + d.engagedVisits())
                .containsExactly("country=:1/0", "browser_family=safari:1/0", "os_family=ios:1/0", "viewport=<480:1/0",
                        "visitor_type=returning:1/0");
    }

    private static VisitFacts facts(Visit visit) {
        return new VisitFacts(visit, true, Channels.classify(visit.firstPageview()), "");
    }
}
