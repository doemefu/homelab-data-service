package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.ClickRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.ConversionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DayRows;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DimensionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.EntryExitRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.FunnelRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.GroupPathRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.IntentSeenRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.TransitionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.PageRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.SectionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.SourceRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.VisitsRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the visits that start on one day into the rows of the WA-3a tables (docs/100 §7.8). Visit-scoped tables are
 * keyed by entry locale and device class, so a visit counts once per path even when it viewed both locales. Open
 * dimensions (referrer domain, the three UTM values, outbound click targets) keep the day's top 50 by visits (ties by
 * key) and fold the rest into {@code (other)} before counting, so the folded rows are exact and unique.
 */
public final class DayAggregator {

    static final int TOP_N = 50;
    public static final String OTHER = "(other)";
    static final String AUTOMATION = "/automation";
    /** Section ids by bit of {@code sectionMask} (docs/100 §3.5, §3.7). */
    public static final List<String> SECTIONS = List.of("hero", "so-arbeite-ich", "beispiel", "preise", "daten",
            "ueber-mich", "faq", "final");
    /** Upper bounds (exclusive) of the engaged-time buckets 0..6; bucket 7 is everything above 10 min. */
    static final long[] HISTOGRAM_BOUNDS_MS = {5_000, 10_000, 30_000, 60_000, 120_000, 300_000, 600_000};
    static final int[] SCROLL_THRESHOLDS = {25, 50, 75, 90, 100};

    private DayAggregator() {
    }

    /**
     * @param included non-orphan, non-excluded visits starting on the day
     * @param excluded excluded (non-orphan) visits starting on the day; they only add to {@code excluded_visits}
     */
    public static DayRows aggregate(List<VisitFacts> included, List<Visit> excluded) {
        return new DayRows(visits(included, excluded), pages(included), sections(included), clicks(included),
                sources(included), conversions(included), transitions(included), funnels(included),
                groupPaths(included), entryExits(included), intentSeen(included), dimensions(included));
    }

    private record TransitionKey(String locale, String device, String from, String to) {
    }

    private static List<TransitionRow> transitions(List<VisitFacts> included) {
        Map<TransitionKey, Integer> counts = new TreeMap<>(Comparator.comparing(TransitionKey::locale)
                .thenComparing(TransitionKey::device).thenComparing(TransitionKey::from)
                .thenComparing(TransitionKey::to));
        for (VisitFacts f : included) {
            Segment s = segment(f.visit());
            for (String[] pair : Journeys.transitions(f.visit())) {
                counts.merge(new TransitionKey(s.locale(), s.device(), pair[0], pair[1]), 1, Integer::sum);
            }
        }
        List<TransitionRow> rows = new ArrayList<>();
        counts.forEach((k, n) -> rows.add(new TransitionRow(k.locale(), k.device(), k.from(), k.to(), n)));
        return rows;
    }

    private record FunnelKey(String funnel, int step, String locale, String device) {
    }

    private static List<FunnelRow> funnels(List<VisitFacts> included) {
        Map<FunnelKey, Integer> counts = new TreeMap<>(Comparator.comparing(FunnelKey::funnel)
                .thenComparingInt(FunnelKey::step).thenComparing(FunnelKey::locale).thenComparing(FunnelKey::device));
        for (VisitFacts f : included) {
            Segment s = segment(f.visit());
            int offer = Journeys.offerSteps(f.visit());
            for (int step = 1; step <= offer; step++) {
                counts.merge(new FunnelKey(Journeys.OFFER, step, s.locale(), s.device()), 1, Integer::sum);
            }
            int contact = Journeys.contactFormSteps(f.visit());
            for (int step = 1; step <= contact; step++) {
                counts.merge(new FunnelKey(Journeys.CONTACT_FORM, step, s.locale(), s.device()), 1, Integer::sum);
            }
        }
        List<FunnelRow> rows = new ArrayList<>();
        counts.forEach((k, n) -> rows.add(new FunnelRow(k.funnel(), k.step(), k.locale(), k.device(), n)));
        return rows;
    }

    private static List<GroupPathRow> groupPaths(List<VisitFacts> included) {
        Map<String, int[]> counts = new TreeMap<>();
        for (VisitFacts f : included) {
            int[] c = counts.computeIfAbsent(Journeys.groupPathKey(f.visit()), k -> new int[2]);
            c[0]++;
            if (f.visit().converting()) {
                c[1]++;
            }
        }
        List<GroupPathRow> rows = new ArrayList<>();
        counts.forEach((k, c) -> rows.add(new GroupPathRow(k, c[0], c[1])));
        return rows;
    }

    private static List<EntryExitRow> entryExits(List<VisitFacts> included) {
        Map<List<String>, Integer> counts = new TreeMap<>(Comparator.<List<String>, String>comparing(k -> k.get(0))
                .thenComparing(k -> k.get(1)));
        for (VisitFacts f : included) {
            counts.merge(List.of(f.visit().entryPath(), f.visit().exitPath()), 1, Integer::sum);
        }
        List<EntryExitRow> rows = new ArrayList<>();
        counts.forEach((k, n) -> rows.add(new EntryExitRow(k.get(0), k.get(1), n)));
        return rows;
    }

    private static List<IntentSeenRow> intentSeen(List<VisitFacts> included) {
        Map<Journeys.Seen, Integer> counts = new java.util.EnumMap<>(Journeys.Seen.class);
        for (VisitFacts f : included) {
            for (Journeys.Seen seen : Journeys.seenBeforeIntent(f.visit())) {
                counts.merge(seen, 1, Integer::sum);
            }
        }
        List<IntentSeenRow> rows = new ArrayList<>();
        counts.forEach((seen, n) -> rows.add(new IntentSeenRow(seen.name().toLowerCase(java.util.Locale.ROOT), n)));
        return rows;
    }

    /** {@code dimension} names of {@code daily_dimension} (docs/100 §7.8). */
    public static final List<String> DIMENSIONS = List.of("country", "browser_family", "os_family", "viewport",
            "visitor_type");

    private static List<DimensionRow> dimensions(List<VisitFacts> included) {
        Set<String> countries = topN(included.stream()
                .map(f -> nullToEmpty(f.visit().firstPageview().country()))
                .collect(Collectors.toMap(Function.identity(), x -> 1, Integer::sum)));
        Map<List<String>, int[]> counts = new TreeMap<>(Comparator.<List<String>, Integer>comparing(
                k -> DIMENSIONS.indexOf(k.get(0))).thenComparing(k -> k.get(1)));
        for (VisitFacts f : included) {
            RawEvent pv = f.visit().firstPageview();
            String country = nullToEmpty(pv.country());
            int engaged = f.visit().engaged() ? 1 : 0;
            for (List<String> key : List.of(
                    List.of("country", countries.contains(country) ? country : OTHER),
                    List.of("browser_family", nullToEmpty(pv.browserFamily())),
                    List.of("os_family", nullToEmpty(pv.osFamily())),
                    List.of("viewport", nullToEmpty(pv.viewport())),
                    List.of("visitor_type", f.isNew() ? "new" : "returning"))) {
                int[] c = counts.computeIfAbsent(key, k -> new int[2]);
                c[0]++;
                c[1] += engaged;
            }
        }
        List<DimensionRow> rows = new ArrayList<>();
        counts.forEach((k, c) -> rows.add(new DimensionRow(k.get(0), k.get(1), c[0], c[1])));
        return rows;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Segment(String locale, String device) {
    }

    private static Segment segment(Visit v) {
        return new Segment(v.entryLocale(), v.deviceClass());
    }

    private static List<VisitsRow> visits(List<VisitFacts> included, List<Visit> excluded) {
        Map<Segment, int[]> counts = new TreeMap<>(Comparator.comparing(Segment::locale)
                .thenComparing(Segment::device));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            int[] c = counts.computeIfAbsent(segment(v), k -> new int[12]);
            c[0]++;
            c[f.isNew() ? 1 : 2]++;
            if (v.engaged()) {
                c[3]++;
            }
            if (v.offer()) {
                c[4]++;
                if (v.engaged()) {
                    c[5]++;
                }
            }
            if (v.contactIntent()) {
                c[6]++;
            }
            if (v.formStart()) {
                c[7]++;
            }
            if (v.converting()) {
                c[8]++;
            }
            c[9] += v.goals();
            c[10] += v.slots().size();
        }
        for (Visit v : excluded) {
            counts.computeIfAbsent(segment(v), k -> new int[12])[11]++;
        }
        List<VisitsRow> rows = new ArrayList<>();
        counts.forEach((s, c) -> rows.add(new VisitsRow(s.locale(), s.device(), c[0], c[1], c[2], c[3], c[4], c[5],
                c[6], c[7], c[8], c[9], c[10], c[11])));
        return rows;
    }

    private record PageKey(String path, String locale, String device) {
    }

    private static final class PageAcc {
        int pageviews;
        int de;
        int en;
        int visitsViewed;
        int entries;
        int exits;
        int landingEngaged;
        int landingConverting;
        int engagementSamples;
        long engagedSum;
        final int[] histogram = new int[8];
        int scrollSamples;
        final int[] scroll = new int[5];
    }

    private static List<PageRow> pages(List<VisitFacts> included) {
        Map<PageKey, PageAcc> acc = new TreeMap<>(Comparator.comparing(PageKey::path).thenComparing(PageKey::locale)
                .thenComparing(PageKey::device));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            Segment s = segment(v);
            Set<String> viewed = new HashSet<>();
            for (PageSlot slot : v.slots()) {
                PageAcc a = acc.computeIfAbsent(new PageKey(slot.path(), s.locale(), s.device()), k -> new PageAcc());
                a.pageviews++;
                if ("de".equals(slot.locale())) {
                    a.de++;
                } else if ("en".equals(slot.locale())) {
                    a.en++;
                }
                if (viewed.add(slot.path())) {
                    a.visitsViewed++;
                }
                if (slot.hasEngagement()) {
                    a.engagementSamples++;
                    a.engagedSum += slot.engagedMs();
                    a.histogram[bucket(slot.engagedMs())]++;
                    if (!slot.fits()) {
                        a.scrollSamples++;
                        for (int i = 0; i < SCROLL_THRESHOLDS.length; i++) {
                            if (slot.scrollMax() != null && slot.scrollMax() >= SCROLL_THRESHOLDS[i]) {
                                a.scroll[i]++;
                            }
                        }
                    }
                }
            }
            PageAcc entry = acc.get(new PageKey(v.entryPath(), s.locale(), s.device()));
            entry.entries++;
            if (v.engaged()) {
                entry.landingEngaged++;
            }
            if (v.converting()) {
                entry.landingConverting++;
            }
            acc.get(new PageKey(v.exitPath(), s.locale(), s.device())).exits++;
        }
        List<PageRow> rows = new ArrayList<>();
        acc.forEach((k, a) -> rows.add(new PageRow(k.path(), k.locale(), k.device(), a.pageviews, a.de, a.en,
                a.visitsViewed, a.entries, a.exits, a.landingEngaged, a.landingConverting, a.engagementSamples,
                a.engagedSum, a.histogram, a.scrollSamples, a.scroll)));
        return rows;
    }

    static int bucket(long engagedMs) {
        for (int i = 0; i < HISTOGRAM_BOUNDS_MS.length; i++) {
            if (engagedMs < HISTOGRAM_BOUNDS_MS[i]) {
                return i;
            }
        }
        return 7;
    }

    private record SectionKey(String locale, String device, String section) {
    }

    private static List<SectionRow> sections(List<VisitFacts> included) {
        Map<SectionKey, int[]> acc = new TreeMap<>(Comparator.comparing(SectionKey::locale)
                .thenComparing(SectionKey::device)
                .thenComparing(k -> SECTIONS.indexOf(k.section())));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            Segment s = segment(v);
            for (PageSlot slot : v.slots()) {
                if (!AUTOMATION.equals(slot.path())) {
                    continue;
                }
                for (int bit = 0; bit < SECTIONS.size(); bit++) {
                    if ((slot.sectionMask() & (1 << bit)) != 0) {
                        acc.computeIfAbsent(new SectionKey(s.locale(), s.device(), SECTIONS.get(bit)),
                                k -> new int[2])[0]++;
                    }
                }
            }
            PageSlot exit = v.slots().getLast();
            if (AUTOMATION.equals(exit.path()) && exit.sectionMask() != 0) {
                int deepest = 31 - Integer.numberOfLeadingZeros(exit.sectionMask() & 0xFF);
                acc.computeIfAbsent(new SectionKey(s.locale(), s.device(), SECTIONS.get(deepest)),
                        k -> new int[2])[1]++;
            }
        }
        List<SectionRow> rows = new ArrayList<>();
        acc.forEach((k, c) -> rows.add(new SectionRow(k.locale(), k.device(), k.section(), c[0], c[1])));
        return rows;
    }

    private record ClickKey(String path, String locale, String device, String kind, String target, String area) {
    }

    private static List<ClickRow> clicks(List<VisitFacts> included) {
        Map<String, Integer> outboundVisits = new HashMap<>();
        for (VisitFacts f : included) {
            f.visit().clicks().stream()
                    .filter(c -> "outbound".equals(c.clickKind()))
                    .map(RawEvent::clickTarget)
                    .distinct()
                    .forEach(t -> outboundVisits.merge(t, 1, Integer::sum));
        }
        Set<String> keptOutbound = topN(outboundVisits);

        Map<ClickKey, int[]> counts = new TreeMap<>(Comparator.comparing(ClickKey::path)
                .thenComparing(ClickKey::locale).thenComparing(ClickKey::device).thenComparing(ClickKey::kind)
                .thenComparing(ClickKey::target).thenComparing(ClickKey::area));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            Segment s = segment(v);
            Set<ClickKey> seen = new HashSet<>();
            for (RawEvent c : v.clicks()) {
                String target = c.clickTarget() == null ? "" : c.clickTarget();
                if ("outbound".equals(c.clickKind()) && !keptOutbound.contains(target)) {
                    target = OTHER;
                }
                ClickKey key = new ClickKey(c.path(), s.locale(), s.device(), c.clickKind(), target, c.clickArea());
                int[] n = counts.computeIfAbsent(key, k -> new int[3]);
                n[0]++;
                if (seen.add(key)) {
                    n[1]++;
                    if (v.converting()) {
                        n[2]++;
                    }
                }
            }
        }
        List<ClickRow> rows = new ArrayList<>();
        counts.forEach((k, n) -> rows.add(new ClickRow(k.path(), k.locale(), k.device(), k.kind(), k.target(),
                k.area(), n[0], n[1], n[2])));
        return rows;
    }

    private record SourceKey(String locale, String device, String channel, String referrer, String utmSource,
                             String utmMedium, String utmCampaign) {
    }

    private static List<SourceRow> sources(List<VisitFacts> included) {
        Function<Function<RawEvent, String>, Set<String>> keep = attr -> topN(included.stream()
                .map(f -> attr.apply(f.visit().firstPageview()))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Function.identity(), x -> 1, Integer::sum)));
        Set<String> referrers = keep.apply(RawEvent::referrerDomain);
        Set<String> sources = keep.apply(RawEvent::utmSource);
        Set<String> mediums = keep.apply(RawEvent::utmMedium);
        Set<String> campaigns = keep.apply(RawEvent::utmCampaign);

        Map<SourceKey, int[]> counts = new TreeMap<>(Comparator.comparing(SourceKey::locale)
                .thenComparing(SourceKey::device).thenComparing(SourceKey::channel)
                .thenComparing(SourceKey::referrer).thenComparing(SourceKey::utmSource)
                .thenComparing(SourceKey::utmMedium).thenComparing(SourceKey::utmCampaign));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            RawEvent pv = v.firstPageview();
            Segment s = segment(v);
            SourceKey key = new SourceKey(s.locale(), s.device(), f.channel(), fold(pv.referrerDomain(), referrers),
                    fold(pv.utmSource(), sources), fold(pv.utmMedium(), mediums), fold(pv.utmCampaign(), campaigns));
            int[] c = counts.computeIfAbsent(key, k -> new int[5]);
            c[0]++;
            if (f.isNew()) {
                c[1]++;
            }
            if (v.engaged()) {
                c[2]++;
            }
            if (v.offer()) {
                c[3]++;
            }
            if (v.converting()) {
                c[4]++;
            }
        }
        List<SourceRow> rows = new ArrayList<>();
        counts.forEach((k, c) -> rows.add(new SourceRow(k.locale(), k.device(), k.channel(), k.referrer(),
                k.utmSource(), k.utmMedium(), k.utmCampaign(), c[0], c[1], c[2], c[3], c[4])));
        return rows;
    }

    private record ConversionKey(String locale, String device, String entryPath, String channel, String firstChannel) {
    }

    private static List<ConversionRow> conversions(List<VisitFacts> included) {
        Map<ConversionKey, int[]> counts = new TreeMap<>(Comparator.comparing(ConversionKey::locale)
                .thenComparing(ConversionKey::device).thenComparing(ConversionKey::entryPath)
                .thenComparing(ConversionKey::channel).thenComparing(ConversionKey::firstChannel));
        for (VisitFacts f : included) {
            Visit v = f.visit();
            if (!v.converting()) {
                continue;
            }
            Segment s = segment(v);
            int[] c = counts.computeIfAbsent(new ConversionKey(s.locale(), s.device(), v.entryPath(), f.channel(),
                    f.firstChannel()), k -> new int[2]);
            c[0]++;
            c[1] += v.goals();
        }
        List<ConversionRow> rows = new ArrayList<>();
        counts.forEach((k, c) -> rows.add(new ConversionRow(k.locale(), k.device(), k.entryPath(), k.channel(),
                k.firstChannel(), c[0], c[1])));
        return rows;
    }

    /** The top {@link #TOP_N} keys by count desc, ties by key asc. */
    static Set<String> topN(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static String fold(String value, Set<String> kept) {
        if (value == null) {
            return "";
        }
        return kept.contains(value) ? value : OTHER;
    }
}
