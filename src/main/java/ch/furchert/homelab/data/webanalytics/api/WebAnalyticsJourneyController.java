package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.KeyCounts;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.Scope;
import ch.furchert.homelab.data.webanalytics.api.AggregateQueryRepository.TransitionAgg;
import ch.furchert.homelab.data.webanalytics.ingest.RejectReason;
import ch.furchert.homelab.data.webanalytics.ingest.WebAnalyticsMetrics;
import ch.furchert.homelab.data.webanalytics.rollup.DayAggregator;
import ch.furchert.homelab.data.webanalytics.rollup.Journeys;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * WA-3b read endpoints {@code GET /api/webanalytics/funnels}, {@code /paths}, {@code /audience}, {@code /coverage}
 * (docs/100 §8.1, §8.2, rev 3.5). Final days only; {@code /funnels} and {@code /paths} start at
 * {@code definitionFrom} (first day of the current rollup definition). Access control and no-store headers come from
 * the {@code /api/webanalytics/**} chain.
 */
@RestController
@RequestMapping("/api/webanalytics")
public class WebAnalyticsJourneyController {

    static final String OTHER = DayAggregator.OTHER;
    static final List<String> SEEN = List.of("about", "projects", "prices", "none");

    private final AggregateQueryRepository queries;
    private final LocalDate goLiveDate;
    private final Clock clock;
    private final ZoneId zone;

    WebAnalyticsJourneyController(AggregateQueryRepository queries, WebAnalyticsProperties properties, Clock clock) {
        this.queries = queries;
        this.goLiveDate = properties.coverage().goLiveDate();
        this.clock = clock;
        this.zone = properties.timezone();
    }

    @GetMapping("/funnels")
    ReadDtos.FunnelsResponse funnels(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String compare,
                                     @RequestParam(required = false) String locale,
                                     @RequestParam(required = false) String device) {
        LocalDate definitionFrom = queries.definitionFrom(RollupPlanner.DEFINITION_VERSION).orElse(null);
        ReadParams.Query q = ReadParams.clampToDefinition(parse(from, to, compare, locale, device, true),
                definitionFrom);
        ReadParams.Range r = q.range();
        List<ReadDtos.Funnel> funnels = new ArrayList<>();
        for (String id : List.of(Journeys.OFFER, Journeys.CONTACT_FORM)) {
            List<String> keys = Journeys.OFFER.equals(id) ? Journeys.OFFER_STEPS : Journeys.CONTACT_FORM_STEPS;
            List<ReadDtos.FunnelStep> current = steps(id, keys, r == null ? null : scope(q, r.from(), r.to()));
            List<ReadDtos.FunnelStep> previous = r != null && r.hasPrevious()
                    ? steps(id, keys, scope(q, r.previousFrom(), r.previousTo())) : null;
            funnels.add(new ReadDtos.Funnel(id, current, previous));
        }
        return new ReadDtos.FunnelsResponse(ReadParams.rangeDto(q), q.filters(), RollupPlanner.DEFINITION_VERSION,
                definitionFrom, funnels);
    }

    @GetMapping("/paths")
    ReadDtos.PathsResponse paths(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) String compare,
                                 @RequestParam(required = false) String locale,
                                 @RequestParam(required = false) String device,
                                 @RequestParam(required = false) String limit) {
        int max = ReadParams.limit(limit);
        LocalDate definitionFrom = queries.definitionFrom(RollupPlanner.DEFINITION_VERSION).orElse(null);
        ReadParams.Query q = ReadParams.clampToDefinition(parse(from, to, compare, locale, device, true),
                definitionFrom);
        ReadParams.Range r = q.range();
        if (r == null) {
            return new ReadDtos.PathsResponse(ReadParams.rangeDto(q), q.filters(), definitionFrom, List.of(),
                    List.of(), new ReadDtos.SeenBeforeContacting(0, SEEN.stream()
                    .map(s -> new ReadDtos.SeenItem(s, 0)).toList()), List.of());
        }
        // Only transitions carry locale/device keys; the other blocks are unfiltered (rev 3.5).
        List<ReadDtos.GroupPath> groupPaths = limitKeys(queries.groupPaths(r.from(), r.to()), max).stream()
                .map(k -> new ReadDtos.GroupPath(k.key().getFirst(), k.visits(), k.convertingVisits())).toList();
        List<ReadDtos.EntryExit> entryExit = limitKeys(queries.entryExits(r.from(), r.to()), max).stream()
                .map(k -> new ReadDtos.EntryExit(k.key().get(0), k.key().size() > 1 ? k.key().get(1) : OTHER,
                        k.visits()))
                .toList();
        Map<String, Long> seen = queries.intentSeen(r.from(), r.to());
        long intentVisits = queries.visitTotals(new Scope(r.from(), r.to(), null, null)).contactIntentVisits();
        ReadDtos.SeenBeforeContacting seenBefore = new ReadDtos.SeenBeforeContacting(intentVisits,
                SEEN.stream().map(s -> new ReadDtos.SeenItem(s, seen.getOrDefault(s, 0L))).toList());
        List<ReadDtos.Transition> transitions = transitionItems(
                queries.transitions(scope(q, r.from(), r.to()), null, null), max);
        return new ReadDtos.PathsResponse(ReadParams.rangeDto(q), q.filters(), definitionFrom, groupPaths, entryExit,
                seenBefore, transitions);
    }

    @GetMapping("/audience")
    ReadDtos.AudienceResponse audience(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String compare,
                                       @RequestParam(required = false) String locale,
                                       @RequestParam(required = false) String device,
                                       @RequestParam(required = false) String limit) {
        int max = ReadParams.limit(limit);
        // rev 3.5: the audience figures (dimensions) exist from the first day of the current definition on.
        ReadParams.Query q = ReadParams.clampToDefinition(parse(from, to, compare, locale, device, false),
                queries.definitionFrom(RollupPlanner.DEFINITION_VERSION).orElse(null));
        ReadParams.Range r = q.range();
        if (r == null) {
            List<ReadDtos.ValueCount> none = List.of();
            return new ReadDtos.AudienceResponse(ReadParams.rangeDto(q), q.filters(),
                    new ReadDtos.NewVsReturning(0, 0), new ReadDtos.ValueBasis(null, null), none, none,
                    new ReadDtos.Dimensions(none, none, none, none, none));
        }
        AggregateQueryRepository.VisitCounts totals = queries.visitTotals(new Scope(r.from(), r.to(), null, null));
        ReadDtos.ValueBasis repeat;
        if (r.monthBasis()) {
            repeat = new ReadDtos.ValueBasis(queries.monthlyRepeatOfferViewers(r.from(), r.to()).orElse(null),
                    "month");
        } else if (r.days() == 28) {
            repeat = new ReadDtos.ValueBasis(queries.repeatOfferViewers28d(r.to()).orElse(null), "28d");
        } else {
            repeat = new ReadDtos.ValueBasis(null, null);
        }
        Map<String, List<ReadDtos.ValueCount>> byDimension = new HashMap<>();
        Map<String, Map<String, Long>> grouped = new HashMap<>();
        queries.dimensions(r.from(), r.to()).forEach(d -> grouped
                .computeIfAbsent(d.dimension(), k -> new HashMap<>()).merge(d.value(), d.visits(), Long::sum));
        for (String dimension : DayAggregator.DIMENSIONS) {
            byDimension.put(dimension, valueCounts(grouped.getOrDefault(dimension, Map.of()), max));
        }
        return new ReadDtos.AudienceResponse(ReadParams.rangeDto(q), q.filters(),
                new ReadDtos.NewVsReturning(totals.newVisits(), totals.returningVisits()), repeat,
                valueCounts(queries.visitsBy("device_class", r.from(), r.to()), max),
                valueCounts(queries.visitsBy("entry_locale", r.from(), r.to()), max),
                new ReadDtos.Dimensions(byDimension.get("country"), byDimension.get("browser_family"),
                        byDimension.get("os_family"), byDimension.get("viewport"), byDimension.get("visitor_type")));
    }

    @GetMapping("/coverage")
    ReadDtos.CoverageResponse coverage(@RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String compare,
                                       @RequestParam(required = false) String locale,
                                       @RequestParam(required = false) String device) {
        ReadParams.Query q = parse(from, to, compare, locale, device, false);
        ReadParams.Range r = q.range();
        ReadDtos.DataQuality quality = dataQuality(r == null ? null : r.from(), r == null ? null : r.to());
        // baselineFrom is null while the go-live date is unset or still in the future (rev 3.5).
        LocalDate baselineFrom = goLiveDate == null || goLiveDate.isAfter(LocalDate.now(clock.withZone(zone)))
                ? null : goLiveDate;
        LocalDate windowFrom = r == null || baselineFrom == null ? null
                : (r.from().isBefore(baselineFrom) ? baselineFrom : r.from());
        if (windowFrom == null || windowFrom.isAfter(r.to())) {
            return new ReadDtos.CoverageResponse(ReadParams.rangeDto(q), q.filters(), baselineFrom, List.of(),
                    List.of(), new ReadDtos.Enquiries(0, 0), quality);
        }
        LocalDate windowTo = r.to();
        Map<LocalDate, Long> pageviews = queries.pageviewsByDay(windowFrom, windowTo);
        Map<LocalDate, AggregateQueryRepository.CfDay> cf = queries.cfByDay(windowFrom, windowTo);
        List<ReadDtos.CoverageDay> daily = new ArrayList<>();
        for (LocalDate d = windowFrom; !d.isAfter(windowTo); d = d.plusDays(1)) {
            AggregateQueryRepository.CfDay c = cf.get(d);
            daily.add(new ReadDtos.CoverageDay(d, pageviews.getOrDefault(d, 0L), c == null ? 0 : c.requests(),
                    c != null && c.sampled()));
        }
        Map<List<String>, long[]> pages = new TreeMap<>(Comparator.<List<String>, String>comparing(k -> k.get(0))
                .thenComparing(k -> k.get(1)));
        Map<List<String>, Boolean> sampled = new HashMap<>();
        queries.pageviewsByPage(windowFrom, windowTo).forEach(p ->
                pages.computeIfAbsent(List.of(p.path(), p.locale()), k -> new long[2])[0] += p.count());
        queries.cfByPage(windowFrom, windowTo).forEach(p -> {
            pages.computeIfAbsent(List.of(p.path(), p.locale()), k -> new long[2])[1] += p.count();
            sampled.merge(List.of(p.path(), p.locale()), p.sampled(), Boolean::logicalOr);
        });
        List<ReadDtos.CoveragePage> byPage = pages.entrySet().stream()
                .filter(e -> e.getValue()[0] > 0 || e.getValue()[1] > 0)
                .map(e -> new ReadDtos.CoveragePage(e.getKey().get(0), e.getKey().get(1), e.getValue()[0],
                        e.getValue()[1], sampled.getOrDefault(e.getKey(), false)))
                .toList();
        ReadDtos.Enquiries enquiries = new ReadDtos.Enquiries(queries.consentedSubmits(windowFrom, windowTo),
                queries.enquiriesTotal(windowFrom, windowTo));
        return new ReadDtos.CoverageResponse(ReadParams.rangeDto(q), q.filters(), baselineFrom, daily, byPage,
                enquiries, quality);
    }

    private ReadParams.Query parse(String from, String to, String compare, String locale, String device,
                                   boolean filtersApply) {
        return ReadParams.parse(from, to, compare, locale, device, filtersApply,
                queries.lastCompleteDay().orElse(null));
    }

    private static Scope scope(ReadParams.Query q, LocalDate from, LocalDate to) {
        return new Scope(from, to, q.locale(), q.device());
    }

    private List<ReadDtos.FunnelStep> steps(String id, List<String> keys, Scope scope) {
        Map<Integer, Long> visits = scope == null ? Map.of() : queries.funnels(scope).stream()
                .filter(f -> f.funnelId().equals(id))
                .collect(Collectors.toMap(AggregateQueryRepository.FunnelAgg::step,
                        AggregateQueryRepository.FunnelAgg::visits));
        List<ReadDtos.FunnelStep> steps = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            steps.add(new ReadDtos.FunnelStep(i + 1, keys.get(i), visits.getOrDefault(i + 1, 0L)));
        }
        return steps;
    }

    /** {@code dataQuality} from {@code daily_ingest_stats} over the range, every known key 0-filled (rev 3.5). */
    private ReadDtos.DataQuality dataQuality(LocalDate from, LocalDate to) {
        Map<String, Long> edge = new LinkedHashMap<>();
        WebAnalyticsMetrics.EDGE_REASONS.forEach(reason -> edge.put(reason, 0L));
        Map<String, Long> secondLine = new LinkedHashMap<>();
        for (RejectReason reason : RejectReason.values()) {
            secondLine.put(reason.label(), 0L);
        }
        long duplicates = 0;
        if (from != null) {
            for (AggregateQueryRepository.StageReason s : queries.ingestStats(from, to)) {
                switch (s.stage()) {
                    case "edge" -> edge.merge(s.reason(), s.events(), Long::sum);
                    case "rejected" -> secondLine.merge(s.reason(), s.events(), Long::sum);
                    case "duplicate" -> duplicates += s.events();
                    default -> {
                        // accepted and later stages are not part of dataQuality
                    }
                }
            }
        }
        return new ReadDtos.DataQuality(edge, secondLine, duplicates, null);
    }

    /** Sorted by visits desc, key asc; the remainder is one {@code (other)} item. */
    private static List<KeyCounts> limitKeys(List<KeyCounts> items, int max) {
        return WebAnalyticsReadController.limit(items, max,
                Comparator.comparingLong(KeyCounts::visits).reversed()
                        .thenComparing(k -> String.join("\u0000", k.key())),
                k -> k.key().contains(OTHER),
                k -> new KeyCounts(List.of(OTHER), k.visits(), k.convertingVisits()),
                (a, b) -> new KeyCounts(List.of(OTHER), a.visits() + b.visits(),
                        a.convertingVisits() + b.convertingVisits()));
    }

    static List<ReadDtos.Transition> transitionItems(List<TransitionAgg> items, int max) {
        return WebAnalyticsReadController.limit(items, max,
                        Comparator.comparingLong(TransitionAgg::count).reversed().thenComparing(TransitionAgg::from)
                                .thenComparing(TransitionAgg::to),
                        t -> false,
                        t -> new TransitionAgg(OTHER, OTHER, t.count()),
                        (a, b) -> new TransitionAgg(OTHER, OTHER, a.count() + b.count()))
                .stream().map(t -> new ReadDtos.Transition(t.from(), t.to(), t.count())).toList();
    }

    /** {@code [{value, visits}]} sorted by visits desc, value asc; {@code ''} → {@code null}; remainder {@code (other)}. */
    private static List<ReadDtos.ValueCount> valueCounts(Map<String, Long> counts, int max) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        return WebAnalyticsReadController.limit(entries, max,
                        Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()),
                        e -> OTHER.equals(e.getKey()),
                        e -> Map.entry(OTHER, e.getValue()),
                        (a, b) -> Map.entry(OTHER, a.getValue() + b.getValue()))
                .stream()
                .map(e -> new ReadDtos.ValueCount(e.getKey().isEmpty() ? null : e.getKey(), e.getValue()))
                .toList();
    }
}
