package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DayRows;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.MonthlyRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.RollupDayRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.VisitorsRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The nightly web analytics rollup (docs/100 §7.5, §7.6, §7.8): turns {@code webanalytics.events} into the id-free
 * WA-3a aggregates. Two triggers, 01:12 and 07:30 Europe/Zurich (documented exception to "crons in UTC"), both
 * through {@link CollectorRunner}, so they share one lock and never overlap. Ships disabled
 * ({@code netmon.collectors.webanalytics-rollup.enabled: false}, gate N1).
 *
 * <p>Each run plans its days from the clock and {@code rollup_day} ({@link RollupPlanner}); each day is one
 * transaction: advisory lock, window load, visits, rows, delete + insert, {@code rollup_day}, mark. A held advisory
 * lock stops the run (warning {@code partial}); any other failure aborts it. Visitor uniques come from the same
 * visit model (distinct visitors of included visits starting in the window), with per-run memoised day summaries.
 */
@Component
public class WebAnalyticsRollupCollector implements NetmonCollector {

    public static final String NAME = "webanalytics-rollup";
    static final Duration MARGIN = Duration.ofHours(6);
    static final int UNIQUES_7D = 7;
    static final int UNIQUES_28D = 28;

    private static final Logger log = LoggerFactory.getLogger(WebAnalyticsRollupCollector.class);

    private final RollupRepository repository;
    private final CollectorStateRepository state;
    private final CollectorRunner runner;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ZurichDays days;
    private final RollupPlanner planner;

    public WebAnalyticsRollupCollector(RollupRepository repository, CollectorStateRepository state,
                                       CollectorRunner runner, PlatformTransactionManager transactionManager,
                                       Clock clock, WebAnalyticsProperties properties) {
        this.repository = repository;
        this.state = state;
        this.runner = runner;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.days = new ZurichDays(properties.timezone());
        this.planner = new RollupPlanner(days);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String group() {
        return WEBANALYTICS_GROUP;
    }

    @Override
    public Duration cadence() {
        return Duration.ofDays(1);
    }

    /** docs/100 §8.2: {@code stale} = last success older than 26 h. */
    @Override
    public Duration staleAfter() {
        return Duration.ofHours(26);
    }

    /** Night run (docs/100 §7.6): yesterday provisional, plus catch-up and finalisation of closed days. */
    @Scheduled(cron = "0 12 1 * * *", zone = "Europe/Zurich")
    public void nightRun() {
        runner.run(this);
    }

    /** Morning run (docs/100 §7.5): rewrites yesterday as final once it closed at 07:15. */
    @Scheduled(cron = "0 30 7 * * *", zone = "Europe/Zurich")
    public void morningRun() {
        runner.run(this);
    }

    @Override
    public void collect() {
        List<LocalDate> plan = planner.plan(clock.instant(), repository.rolledDays());
        Map<LocalDate, DaySummary> memo = new HashMap<>();
        Set<YearMonth> months = new LinkedHashSet<>();
        int written = 0;
        for (LocalDate day : plan) {
            Boolean done = transactions.execute(status -> rollDay(day, memo));
            if (!Boolean.TRUE.equals(done)) {
                throw new CollectorWarning(ErrorCode.PARTIAL,
                        "advisory lock held by another session; run stopped after " + written + " day(s)");
            }
            written++;
            months.add(YearMonth.from(day));
            if (day.getDayOfMonth() <= 2) {
                months.add(YearMonth.from(day).minusMonths(1));
            }
        }
        LocalDate yesterday = days.dayOf(clock.instant()).minusDays(1);
        for (YearMonth month : months) {
            Boolean done = transactions.execute(status -> rollMonth(month, yesterday, memo));
            if (!Boolean.TRUE.equals(done)) {
                throw new CollectorWarning(ErrorCode.PARTIAL,
                        "advisory lock held by another session; monthly visitors not written");
            }
        }
    }

    private boolean rollDay(LocalDate day, Map<LocalDate, DaySummary> memo) {
        if (!repository.tryLock()) {
            return false;
        }
        repository.applyDaySettings();
        Instant now = clock.instant();
        DayVisits computed = computeDay(day, true);
        memo.put(day, computed.summary());

        Set<UUID> visitors7d = new HashSet<>();
        Set<UUID> visitors28d = new HashSet<>();
        Map<UUID, Integer> offerVisits28d = new HashMap<>();
        for (int back = 0; back < UNIQUES_28D; back++) {
            LocalDate d = day.minusDays(back);
            DaySummary s = memo.computeIfAbsent(d, k -> computeDay(k, false).summary());
            if (back < UNIQUES_7D) {
                visitors7d.addAll(s.visitors());
            }
            visitors28d.addAll(s.visitors());
            s.offerVisits().forEach((visitor, n) -> offerVisits28d.merge(visitor, n, Integer::sum));
        }
        DaySummary own = computed.summary();
        int newVisitors = own.newVisitors().size();
        VisitorsRow visitors = new VisitorsRow(own.visitors().size(), newVisitors, own.visitors().size() - newVisitors,
                visitors7d.size(), visitors28d.size(),
                (int) offerVisits28d.values().stream().filter(n -> n >= 2).count());

        int[] raw = repository.rawCounts(days.start(day), days.start(day.plusDays(1)));
        boolean fin = days.isClosed(day, now);
        repository.replaceDay(day, computed.rows(), visitors, new RollupDayRow(day, raw[0], raw[1],
                computed.included(), computed.orphans(), RollupPlanner.DEFINITION_VERSION, fin, now));
        repository.allFinalPrefixEnd()
                .ifPresent(end -> state.updateWindowEnd(NAME, days.start(end.plusDays(1))));
        log.info("[{}] day={} final={} visits={} orphan={} excluded={}", NAME, day, fin, computed.included(),
                computed.orphans(), computed.excluded());
        return true;
    }

    private boolean rollMonth(YearMonth month, LocalDate yesterday, Map<LocalDate, DaySummary> memo) {
        if (!repository.tryLock()) {
            return false;
        }
        repository.applyDaySettings();
        LocalDate last = month.atEndOfMonth().isBefore(yesterday) ? month.atEndOfMonth() : yesterday;
        Set<UUID> visitors = new HashSet<>();
        Set<UUID> newVisitors = new HashSet<>();
        Map<UUID, Integer> offerVisits = new HashMap<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(last); d = d.plusDays(1)) {
            DaySummary s = memo.computeIfAbsent(d, k -> computeDay(k, false).summary());
            visitors.addAll(s.visitors());
            newVisitors.addAll(s.newVisitors());
            s.offerVisits().forEach((visitor, n) -> offerVisits.merge(visitor, n, Integer::sum));
        }
        repository.replaceMonth(new MonthlyRow(month.atDay(1), visitors.size(), newVisitors.size(),
                visitors.size() - newVisitors.size(),
                (int) offerVisits.values().stream().filter(n -> n >= 2).count()));
        return true;
    }

    /** Per-day visitor facts for uniques; held in memory for one run only, never stored. */
    record DaySummary(Set<UUID> visitors, Set<UUID> newVisitors, Map<UUID, Integer> offerVisits) {
    }

    record DayVisits(DaySummary summary, DayRows rows, int included, int orphans, int excluded) {
    }

    /** Builds the visits starting on {@code day}; with {@code withRows} also the aggregate rows. */
    DayVisits computeDay(LocalDate day, boolean withRows) {
        Instant from = days.start(day).minus(MARGIN);
        Instant to = days.start(day.plusDays(1)).plus(MARGIN);
        List<RawEvent> events = repository.loadWindow(from, to);
        Map<UUID, Instant> earlier = repository.earliestBefore(from, to);

        Map<UUID, List<RawEvent>> byVisitor = new LinkedHashMap<>();
        for (RawEvent e : events) {
            byVisitor.computeIfAbsent(e.visitorId(), k -> new ArrayList<>()).add(e);
        }
        List<Visit> included = new ArrayList<>();
        List<Boolean> includedNew = new ArrayList<>();
        List<Visit> excluded = new ArrayList<>();
        int orphans = 0;
        for (Map.Entry<UUID, List<RawEvent>> entry : byVisitor.entrySet()) {
            Instant firstSeenInWindow = entry.getValue().getFirst().occurredAt();
            for (Visit visit : VisitBuilder.build(entry.getValue())) {
                if (!days.dayOf(visit.attributedAt()).equals(day)) {
                    continue;
                }
                if (visit.orphan()) {
                    orphans++;
                } else if (visit.excluded()) {
                    excluded.add(visit);
                } else {
                    Instant firstPageviewAt = visit.firstPageview().occurredAt();
                    boolean isNew = !earlier.containsKey(entry.getKey())
                            && !firstSeenInWindow.isBefore(firstPageviewAt);
                    included.add(visit);
                    includedNew.add(isNew);
                }
            }
        }

        Set<UUID> visitors = new HashSet<>();
        Set<UUID> newVisitors = new HashSet<>();
        Map<UUID, Integer> offerVisits = new HashMap<>();
        for (int i = 0; i < included.size(); i++) {
            Visit v = included.get(i);
            visitors.add(v.visitorId());
            if (includedNew.get(i)) {
                newVisitors.add(v.visitorId());
            }
            if (v.offer()) {
                offerVisits.merge(v.visitorId(), 1, Integer::sum);
            }
        }
        DaySummary summary = new DaySummary(visitors, newVisitors, offerVisits);
        if (!withRows) {
            return new DayVisits(summary, null, included.size(), orphans, excluded.size());
        }

        boolean anyConverting = included.stream().anyMatch(Visit::converting);
        Map<UUID, RawEvent> firstLandings = anyConverting ? repository.firstLandings(from, to) : Map.of();
        List<VisitFacts> facts = new ArrayList<>(included.size());
        for (int i = 0; i < included.size(); i++) {
            Visit v = included.get(i);
            RawEvent firstLanding = firstLandings.get(v.visitorId());
            boolean knownBefore = firstLanding != null
                    && !firstLanding.occurredAt().isAfter(v.firstPageview().occurredAt());
            String firstChannel = v.converting() && knownBefore ? Channels.classify(firstLanding) : "";
            facts.add(new VisitFacts(v, includedNew.get(i), Channels.classify(v.firstPageview()), firstChannel));
        }
        return new DayVisits(summary, DayAggregator.aggregate(facts, excluded), included.size(), orphans,
                excluded.size());
    }
}
