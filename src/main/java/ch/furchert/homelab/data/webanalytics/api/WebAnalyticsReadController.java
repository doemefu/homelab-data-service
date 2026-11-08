package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.web.NetmonApiException;
import ch.furchert.homelab.data.web.ProblemCode;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.ClickAgg;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.ConversionAgg;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.PageAgg;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.Scope;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.SourceAgg;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.VisitCounts;
import ch.furchert.homelab.data.webanalytics.ingest.EventValidator;
import ch.furchert.homelab.data.webanalytics.rollup.Channels;
import ch.furchert.homelab.data.webanalytics.rollup.DayAggregator;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * {@code GET /api/webanalytics/overview}, {@code /pages}, {@code /pages/detail}, {@code /clicks}, {@code /sources}
 * (docs/100 §8.1, §8.2, rev 3.4). Final days only; counts only (rates, intervals and medians are furchert-ch's);
 * lists keep {@code limit} items and fold the remainder into one {@code (other)} item, merged with stored
 * {@code (other)} rows. Access control and no-store headers come from the {@code /api/webanalytics/**} chain.
 */
@RestController
@RequestMapping("/api/webanalytics")
public class WebAnalyticsReadController {

    static final String OTHER = DayAggregator.OTHER;
    static final String AUTOMATION = "/automation";
    static final Set<String> CLICK_KINDS = Set.of("cta", "link", "faq", "lang", "nav", "outbound", "mailto", "tel");
    /** Internal marker of the folded {@code /clicks} remainder (never serialised). */
    private static final String REMAINDER = "\u0000remainder";
    /** Default {@code limit} of the detail drilldown's previous/next lists (docs/100 §8.1, rev 3.5). */
    static final int DETAIL_LIMIT_DEFAULT = 5;

    private final AggregateQueryRepository queries;

    WebAnalyticsReadController(AggregateQueryRepository queries) {
        this.queries = queries;
    }

    @GetMapping("/overview")
    ReadDtos.OverviewResponse overview(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String compare,
                                       @RequestParam(required = false) String locale,
                                       @RequestParam(required = false) String device) {
        ReadParams.Query q = parse(from, to, compare, locale, device);
        ReadParams.Range r = q.range();
        if (r == null) {
            return new ReadDtos.OverviewResponse(ReadParams.rangeDto(q), q.filters(), ReadDtos.Counts.ZERO, null, null,
                    List.of());
        }
        ReadDtos.Counts current = counts(queries.visitTotals(scope(q, r.from(), r.to())),
                queries.enquiriesTotal(r.from(), r.to()));
        ReadDtos.Counts previous = r.hasPrevious()
                ? counts(queries.visitTotals(scope(q, r.previousFrom(), r.previousTo())),
                queries.enquiriesTotal(r.previousFrom(), r.previousTo()))
                : null;
        Map<LocalDate, VisitCounts> byDay = queries.visitsByDay(scope(q, r.from(), r.to()));
        Map<LocalDate, Long> enquiries = queries.enquiriesByDay(r.from(), r.to());
        List<ReadDtos.DailyPoint> daily = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            VisitCounts c = byDay.get(d);
            long enquiriesOfDay = enquiries.getOrDefault(d, 0L);
            daily.add(c == null
                    ? new ReadDtos.DailyPoint(d, 0, 0, 0, 0, 0, 0, enquiriesOfDay)
                    : new ReadDtos.DailyPoint(d, c.visits(), c.offerVisits(), c.engagedVisits(),
                    c.contactIntentVisits(), c.convertingVisits(), c.pageviews(), enquiriesOfDay));
        }
        return new ReadDtos.OverviewResponse(ReadParams.rangeDto(q), q.filters(), current, previous, visitors(q, r),
                daily);
    }

    @GetMapping("/pages")
    ReadDtos.PagesResponse pages(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) String compare,
                                 @RequestParam(required = false) String locale,
                                 @RequestParam(required = false) String device,
                                 @RequestParam(required = false) String limit) {
        int max = ReadParams.limit(limit);
        ReadParams.Query q = parse(from, to, compare, locale, device);
        if (q.range() == null) {
            return new ReadDtos.PagesResponse(ReadParams.rangeDto(q), q.filters(), List.of());
        }
        List<PageAgg> pages = queries.pages(scope(q, q.range().from(), q.range().to()), null);
        List<PageAgg> limited = limit(pages, max, Comparator.comparingLong(PageAgg::pageviews).reversed()
                        .thenComparing(PageAgg::path), p -> OTHER.equals(p.path()),
                p -> mergePages(OTHER, p, emptyPage(OTHER)), (a, b) -> mergePages(OTHER, a, b));
        return new ReadDtos.PagesResponse(ReadParams.rangeDto(q), q.filters(),
                limited.stream().map(WebAnalyticsReadController::pageItem).toList());
    }

    @GetMapping("/pages/detail")
    ReadDtos.PageDetailResponse pageDetail(@RequestParam(required = false) String path,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String compare,
                                           @RequestParam(required = false) String locale,
                                           @RequestParam(required = false) String device,
                                           @RequestParam(required = false) String limit) {
        if (path == null || !EventValidator.isCanonicalPath(path)) {
            throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                    "path must be a locale-free canonical path");
        }
        int max = ReadParams.limit(limit, DETAIL_LIMIT_DEFAULT);
        ReadParams.Query q = parse(from, to, compare, locale, device);
        boolean automation = AUTOMATION.equals(path);
        ReadParams.Range r = q.range();
        if (r == null) {
            return new ReadDtos.PageDetailResponse(ReadParams.rangeDto(q), q.filters(), pageItem(emptyPage(path)),
                    List.of(), List.of(), List.of(), automation ? emptySections() : null,
                    automation ? List.of() : null,
                    List.of());
        }
        Scope scope = scope(q, r.from(), r.to());
        PageAgg summary = queries.pages(scope, path).stream().findFirst().orElse(emptyPage(path));
        List<ClickAgg> clickAggs = queries.clicks(scope, path, null);
        List<ReadDtos.DetailClick> clicks = clickAggs.stream()
                .sorted(clickOrder())
                .map(c -> new ReadDtos.DetailClick(c.kind(), nullIfEmpty(c.target()), c.area(), c.clicks(), c.visits(),
                        c.convertingVisits()))
                .toList();
        ReadDtos.Sections sections = null;
        List<ReadDtos.FaqOpen> faqOpens = null;
        if (automation) {
            Map<String, AggregateQueryRepository.SectionAgg> bySection = queries.sections(scope).stream()
                    .collect(Collectors.toMap(AggregateQueryRepository.SectionAgg::section, s -> s));
            sections = new ReadDtos.Sections(
                    DayAggregator.SECTIONS.stream().map(s -> new ReadDtos.SectionReached(s,
                            bySection.containsKey(s) ? bySection.get(s).reached() : 0)).toList(),
                    DayAggregator.SECTIONS.stream().map(s -> new ReadDtos.SectionExit(s,
                            bySection.containsKey(s) ? bySection.get(s).deepestAtExit() : 0)).toList());
            Map<String, long[]> faq = new LinkedHashMap<>();
            clickAggs.stream().filter(c -> "faq".equals(c.kind())).forEach(c -> {
                long[] acc = faq.computeIfAbsent(c.target(), k -> new long[2]);
                acc[0] += c.clicks();
                acc[1] += c.visits();
            });
            faqOpens = faq.entrySet().stream()
                    .map(e -> new ReadDtos.FaqOpen(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .sorted(Comparator.comparingLong(ReadDtos.FaqOpen::opens).reversed()
                            .thenComparing(ReadDtos.FaqOpen::faq))
                    .toList();
        }
        Map<LocalDate, AggregateQueryRepository.PageDay> byDay = queries.pageDaily(scope, path).stream()
                .collect(Collectors.toMap(AggregateQueryRepository.PageDay::day, d -> d));
        List<ReadDtos.PageDaily> daily = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            AggregateQueryRepository.PageDay pd = byDay.get(d);
            daily.add(new ReadDtos.PageDaily(d, pd == null ? 0 : pd.pageviews(), pd == null ? 0 : pd.visitsViewed()));
        }
        // WA-3b: previous/next pages from daily_transition, incl. (entrance)/(exit), top `limit` + (other); bounded by
        // definitionFrom, because transitions exist from the first day of the current definition on (rev 3.5).
        LocalDate definitionFrom = queries.definitionFrom(RollupPlanner.DEFINITION_VERSION).orElse(null);
        List<ReadDtos.PathCount> previous = List.of();
        List<ReadDtos.PathCount> next = List.of();
        if (definitionFrom != null && !r.to().isBefore(definitionFrom)) {
            Scope transitionScope = scope(q, r.from().isBefore(definitionFrom) ? definitionFrom : r.from(), r.to());
            previous = pathCounts(queries.transitions(transitionScope, null, path), true, max);
            next = pathCounts(queries.transitions(transitionScope, path, null), false, max);
        }
        return new ReadDtos.PageDetailResponse(ReadParams.rangeDto(q), q.filters(), pageItem(summary), previous, next,
                clicks, sections, faqOpens, daily);
    }

    @GetMapping("/clicks")
    ReadDtos.ClicksResponse clicks(@RequestParam(required = false) String kind,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) String compare,
                                   @RequestParam(required = false) String locale,
                                   @RequestParam(required = false) String device,
                                   @RequestParam(required = false) String limit) {
        if (kind != null && !kind.isBlank() && !CLICK_KINDS.contains(kind)) {
            throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                    "kind must be one of cta, link, faq, lang, nav, outbound, mailto, tel");
        }
        String kindFilter = kind == null || kind.isBlank() ? null : kind;
        int max = ReadParams.limit(limit);
        ReadParams.Query q = parse(from, to, compare, locale, device);
        if (q.range() == null) {
            return new ReadDtos.ClicksResponse(ReadParams.rangeDto(q), q.filters(), List.of());
        }
        Scope scope = scope(q, q.range().from(), q.range().to());
        Map<String, Long> pageVisits = queries.pages(scope, null).stream()
                .collect(Collectors.toMap(PageAgg::path, PageAgg::visitsViewed));
        // Remainder (rev 3.5): one item with kind null (mixed kinds; the filtered kind with a kind filter), target
        // "(other)", path/area/pageVisits null and summed counts, merged with the stored outbound "(other)" rows; a nav
        // "(other)" target stays its own item. REMAINDER marks it internally.
        List<ClickAgg> limited = limit(queries.clicks(scope, null, kindFilter), max, clickOrder(),
                c -> "outbound".equals(c.kind()) && OTHER.equals(c.target()),
                c -> new ClickAgg(REMAINDER, OTHER, REMAINDER, REMAINDER, c.clicks(), c.visits(), c.convertingVisits()),
                (a, b) -> new ClickAgg(REMAINDER, OTHER, REMAINDER, REMAINDER, a.clicks() + b.clicks(),
                        a.visits() + b.visits(), a.convertingVisits() + b.convertingVisits()));
        List<ReadDtos.ClickItem> items = limited.stream()
                .map(c -> REMAINDER.equals(c.area())
                        ? new ReadDtos.ClickItem(kindFilter, OTHER, null, null, c.clicks(), c.visits(),
                        c.convertingVisits(), null)
                        : new ReadDtos.ClickItem(c.kind(), nullIfEmpty(c.target()), c.path(), c.area(), c.clicks(),
                        c.visits(), c.convertingVisits(), pageVisits.getOrDefault(c.path(), 0L)))
                .toList();
        return new ReadDtos.ClicksResponse(ReadParams.rangeDto(q), q.filters(), items);
    }

    @GetMapping("/sources")
    ReadDtos.SourcesResponse sources(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String compare,
                                     @RequestParam(required = false) String locale,
                                     @RequestParam(required = false) String device,
                                     @RequestParam(required = false) String limit) {
        int max = ReadParams.limit(limit);
        ReadParams.Query q = parse(from, to, compare, locale, device);
        List<SourceAgg> sources = q.range() == null ? List.of()
                : queries.sources(scope(q, q.range().from(), q.range().to()));
        List<ConversionAgg> conversions = q.range() == null ? List.of()
                : queries.conversions(scope(q, q.range().from(), q.range().to()));

        Map<String, long[]> byChannel = new LinkedHashMap<>();
        Channels.ALL.forEach(c -> byChannel.put(c, new long[5]));
        Map<List<String>, long[]> referrers = new LinkedHashMap<>();
        Map<List<String>, long[]> campaigns = new LinkedHashMap<>();
        for (SourceAgg s : sources) {
            long[] c = byChannel.get(s.channel());
            c[0] += s.visits();
            c[1] += s.newVisits();
            c[2] += s.engagedVisits();
            c[3] += s.offerVisits();
            c[4] += s.convertingVisits();
            if (!s.referrer().isEmpty()) {
                long[] r = referrers.computeIfAbsent(List.of(s.referrer(), s.channel()), k -> new long[2]);
                r[0] += s.visits();
                r[1] += s.convertingVisits();
            }
            if (!s.utmSource().isEmpty()) {
                long[] r = campaigns.computeIfAbsent(List.of(s.utmSource(), s.utmMedium(), s.utmCampaign()),
                        k -> new long[2]);
                r[0] += s.visits();
                r[1] += s.convertingVisits();
            }
        }
        List<ReadDtos.ChannelItem> channels = byChannel.entrySet().stream()
                .map(e -> new ReadDtos.ChannelItem(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], e.getValue()[4]))
                .toList();
        List<ReadDtos.ReferrerItem> referrerItems = limitKeyed(referrers, max, k -> OTHER.equals(k.getFirst()))
                .stream()
                .map(e -> e.getKey() == null
                        ? new ReadDtos.ReferrerItem(OTHER, null, e.getValue()[0], e.getValue()[1])
                        : new ReadDtos.ReferrerItem(e.getKey().get(0), e.getKey().get(1), e.getValue()[0],
                        e.getValue()[1]))
                .toList();
        List<ReadDtos.CampaignItem> campaignItems = limitKeyed(campaigns, max, k -> k.contains(OTHER)).stream()
                .map(e -> e.getKey() == null
                        ? new ReadDtos.CampaignItem(OTHER, null, null, e.getValue()[0], e.getValue()[1])
                        : new ReadDtos.CampaignItem(e.getKey().get(0), nullIfEmpty(e.getKey().get(1)),
                        nullIfEmpty(e.getKey().get(2)), e.getValue()[0], e.getValue()[1]))
                .toList();

        Map<String, long[]> byLanding = new LinkedHashMap<>();
        Map<String, long[]> byFirst = new LinkedHashMap<>();
        Map<String, long[]> byEntry = new LinkedHashMap<>();
        for (ConversionAgg c : conversions) {
            add(byLanding, c.channel(), c);
            add(byFirst, c.firstChannel(), c);
            add(byEntry, c.entryPath(), c);
        }
        ReadDtos.Converters converters = new ReadDtos.Converters(channelConversions(byLanding),
                channelConversions(byFirst),
                byEntry.entrySet().stream()
                        .map(e -> new ReadDtos.PathConversions(e.getKey(), e.getValue()[0], e.getValue()[1]))
                        .sorted(Comparator.comparingLong(ReadDtos.PathConversions::convertingVisits).reversed()
                                .thenComparing(ReadDtos.PathConversions::path))
                        .toList());
        return new ReadDtos.SourcesResponse(ReadParams.rangeDto(q), q.filters(), channels, referrerItems,
                campaignItems, converters);
    }

    private ReadParams.Query parse(String from, String to, String compare, String locale, String device) {
        return ReadParams.parse(from, to, compare, locale, device, true, queries.lastCompleteDay().orElse(null));
    }

    private static Scope scope(ReadParams.Query q, LocalDate from, LocalDate to) {
        return new Scope(from, to, q.locale(), q.device());
    }

    /** Visitors only without filters and for a whole month, exactly 7 or exactly 28 days (docs/100 §8.2, rev 3.4). */
    private ReadDtos.Visitors visitors(ReadParams.Query q, ReadParams.Range r) {
        if (q.filtered()) {
            return null;
        }
        if (r.monthBasis()) {
            ReadDtos.ValueBasis current = new ReadDtos.ValueBasis(
                    queries.monthlyVisitors(r.from(), r.to()).orElse(null), "month");
            ReadDtos.ValueBasis previous = r.hasPrevious()
                    ? new ReadDtos.ValueBasis(queries.monthlyVisitors(r.previousFrom(), r.previousTo()).orElse(null),
                    "month")
                    : null;
            return new ReadDtos.Visitors(current, previous);
        }
        if (r.days() == 7 || r.days() == 28) {
            String basis = r.days() + "d";
            ReadDtos.ValueBasis current = new ReadDtos.ValueBasis(
                    queries.rollingVisitors(r.to(), r.days()).orElse(null), basis);
            ReadDtos.ValueBasis previous = r.hasPrevious()
                    ? new ReadDtos.ValueBasis(queries.rollingVisitors(r.previousTo(), r.days()).orElse(null), basis)
                    : null;
            return new ReadDtos.Visitors(current, previous);
        }
        return null;
    }

    private static ReadDtos.Counts counts(VisitCounts c, long enquiries) {
        return new ReadDtos.Counts(c.visits(), c.newVisits(), c.returningVisits(), c.engagedVisits(), c.offerVisits(),
                c.engagedOfferVisits(), c.contactIntentVisits(), c.formStartVisits(), c.convertingVisits(),
                c.conversions(), c.pageviews(), c.excludedVisits(), enquiries);
    }

    private static ReadDtos.PageItem pageItem(PageAgg p) {
        return new ReadDtos.PageItem(p.path(), p.pageviews(), new ReadDtos.ByLocale(p.de(), p.en()), p.visitsViewed(),
                p.entries(), p.exits(), p.landingEngaged(), p.landingConverting(), p.samples(), p.engagedSum(),
                Arrays.stream(p.histogram()).boxed().toList(), p.scrollSamples(),
                new ReadDtos.ScrollReached(p.scroll()[0], p.scroll()[1], p.scroll()[2], p.scroll()[3], p.scroll()[4]));
    }

    private static PageAgg emptyPage(String path) {
        return new PageAgg(path, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new long[8], 0, new long[5]);
    }

    private static ReadDtos.Sections emptySections() {
        return new ReadDtos.Sections(
                DayAggregator.SECTIONS.stream().map(s -> new ReadDtos.SectionReached(s, 0)).toList(),
                DayAggregator.SECTIONS.stream().map(s -> new ReadDtos.SectionExit(s, 0)).toList());
    }

    private static PageAgg mergePages(String path, PageAgg a, PageAgg b) {
        long[] histogram = new long[8];
        long[] scroll = new long[5];
        for (int i = 0; i < 8; i++) {
            histogram[i] = a.histogram()[i] + b.histogram()[i];
        }
        for (int i = 0; i < 5; i++) {
            scroll[i] = a.scroll()[i] + b.scroll()[i];
        }
        return new PageAgg(path, a.pageviews() + b.pageviews(), a.de() + b.de(), a.en() + b.en(),
                a.visitsViewed() + b.visitsViewed(), a.entries() + b.entries(), a.exits() + b.exits(),
                a.landingEngaged() + b.landingEngaged(), a.landingConverting() + b.landingConverting(),
                a.samples() + b.samples(), a.engagedSum() + b.engagedSum(), histogram,
                a.scrollSamples() + b.scrollSamples(), scroll);
    }

    private static Comparator<ClickAgg> clickOrder() {
        return Comparator.comparingLong(ClickAgg::clicks).reversed()
                .thenComparing(ClickAgg::kind).thenComparing(ClickAgg::target).thenComparing(ClickAgg::path)
                .thenComparing(ClickAgg::area);
    }

    /**
     * The first {@code max} items (stored {@code (other)} rows excluded), plus one {@code (other)} item merging the
     * remainder with the stored {@code (other)} rows.
     */
    static <T> List<T> limit(List<T> items, int max, Comparator<T> order, Predicate<T> isOther,
                             UnaryOperator<T> asOther, BinaryOperator<T> merge) {
        List<T> regular = items.stream().filter(isOther.negate()).sorted(order).toList();
        List<T> rest = new ArrayList<>(items.stream().filter(isOther).toList());
        if (regular.size() > max) {
            rest.addAll(regular.subList(max, regular.size()));
        }
        List<T> result = new ArrayList<>(regular.subList(0, Math.min(max, regular.size())));
        rest.stream().map(asOther).reduce(merge).ifPresent(result::add);
        return result;
    }

    /** {@link #limit} for keyed counters {@code [visits, convertingVisits]}; the remainder entry has a null key. */
    private static List<Map.Entry<List<String>, long[]>> limitKeyed(Map<List<String>, long[]> counts, int max,
                                                                     Predicate<List<String>> isOther) {
        List<Map.Entry<List<String>, long[]>> entries = new ArrayList<>(counts.entrySet());
        return limit(entries, max,
                Comparator.<Map.Entry<List<String>, long[]>>comparingLong(e -> e.getValue()[0]).reversed()
                        .thenComparing(e -> String.join("\u0000", e.getKey())),
                e -> e.getKey() != null && isOther.test(e.getKey()),
                e -> new AbstractMap.SimpleImmutableEntry<List<String>, long[]>(null, e.getValue()),
                (a, b) -> new AbstractMap.SimpleImmutableEntry<List<String>, long[]>(null,
                        new long[]{a.getValue()[0] + b.getValue()[0], a.getValue()[1] + b.getValue()[1]}));
    }

    private static void add(Map<String, long[]> map, String key, ConversionAgg c) {
        long[] acc = map.computeIfAbsent(key, k -> new long[2]);
        acc[0] += c.convertingVisits();
        acc[1] += c.conversions();
    }

    private static List<ReadDtos.ChannelConversions> channelConversions(Map<String, long[]> map) {
        return map.entrySet().stream()
                .map(e -> new ReadDtos.ChannelConversions(nullIfEmpty(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(ReadDtos.ChannelConversions::convertingVisits).reversed()
                        .thenComparing(c -> c.channel() == null ? "" : c.channel()))
                .toList();
    }

    /** Previous ({@code fromPath} side) or next ({@code toPath} side) pages of a transition list. */
    private static List<ReadDtos.PathCount> pathCounts(List<AggregateQueryRepository.TransitionAgg> transitions,
                                                       boolean previous, int max) {
        Map<String, Long> counts = new LinkedHashMap<>();
        transitions.forEach(t -> counts.merge(previous ? t.from() : t.to(), t.count(), Long::sum));
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        return limit(entries, max,
                        Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()),
                        e -> false,
                        e -> Map.entry(OTHER, e.getValue()),
                        (a, b) -> Map.entry(OTHER, a.getValue() + b.getValue()))
                .stream().map(e -> new ReadDtos.PathCount(e.getKey(), e.getValue())).toList();
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
