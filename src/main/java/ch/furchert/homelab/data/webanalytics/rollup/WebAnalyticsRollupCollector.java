package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.config.RetentionProperties;
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
    /**
     * A fault that hits every day (slow scan, overloaded database) stops the CF baseline backfill, and separately the
     * refresh of rolled days, after this many failed days in a row.
     */
    static final int MAX_CONSECUTIVE_CF_BASELINE_FAILURES = 3;

    private static final Logger log = LoggerFactory.getLogger(WebAnalyticsRollupCollector.class);

    private final RollupRepository repository;
    private final CollectorStateRepository state;
    private final CollectorRunner runner;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ZurichDays days;
    private final RollupPlanner planner;
    private final int netmonRetentionDays;

    public WebAnalyticsRollupCollector(RollupRepository repository, CollectorStateRepository state,
                                       CollectorRunner runner, PlatformTransactionManager transactionManager,
                                       Clock clock, WebAnalyticsProperties properties,
                                       RetentionProperties retention) {
        this.repository = repository;
        this.state = state;
        this.runner = runner;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.days = new ZurichDays(properties.timezone());
        this.planner = new RollupPlanner(days);
        this.netmonRetentionDays = retention.inboundRequestGroupsDays();
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
        String cfProblems = cfBaseline(plan);
        if (cfProblems != null) {
            throw new CollectorWarning(ErrorCode.PARTIAL, cfProblems);
        }
    }

    /**
     * The Cloudflare baseline (docs/100 §7.8, rev 3.5), after the analytics days and in separate transactions, so a
     * failure here never aborts the rollup: first the backfill of every missing day ({@link #backfillCfBaseline}),
     * then a replace for every day rolled in this run ({@link #refreshCfBaseline}). A held advisory lock stops both;
     * any other failure is logged by class name and the remaining days still run, up to
     * {@link #MAX_CONSECUTIVE_CF_BASELINE_FAILURES} failures in a row. All problems are returned as one warning message
     * (null = none).
     */
    String cfBaseline(List<LocalDate> rolled) {
        List<String> problems = new ArrayList<>();
        Set<LocalDate> failedBackfillDays = new HashSet<>();
        if (backfillCfBaseline(problems, failedBackfillDays)) {
            refreshCfBaseline(rolled, failedBackfillDays, problems);
        }
        if (problems.isEmpty()) {
            return null;
        }
        String message = String.join("; ", problems);
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    /**
     * Backfill (docs/100 §7.8) in per-day chunks: every day netmon still holds from the second one (the oldest is cut
     * by netmon's retention and therefore partial) to yesterday that has no baseline row yet. On an empty table this
     * is the one-time pre-launch backfill; when a run fails part-way, the next run resumes with the days still
     * missing. Each day is its own statement and transaction ({@link #backfillCfBaselineDay}), so one slow day
     * (statement timeout) cannot block the others; after {@link #MAX_CONSECUTIVE_CF_BASELINE_FAILURES} failed days in
     * a row the run stops and leaves the remaining days to the next run. Exempt from {@link RollupPlanner#eligible}: it
     * writes neither event-derived tables nor {@code rollup_day}. Nothing in netmon yet → nothing written, retried
     * next run.
     *
     * @param failedDays receives the days whose backfill failed in this run
     * @return false when the advisory lock was held (the run stops there), true otherwise
     */
    boolean backfillCfBaseline(List<String> problems, Set<LocalDate> failedDays) {
        List<LocalDate> missing;
        try {
            missing = transactions.execute(status -> missingCfBaselineDays());
        } catch (RuntimeException e) {
            log.warn("[{}] CF baseline backfill failed: {}", NAME, e.getClass().getSimpleName());
            problems.add("CF baseline backfill failed (" + e.getClass().getSimpleName() + ")");
            return true;
        }
        int processed = 0; // days written, including days for which netmon yields no row
        int failed = 0;
        int consecutive = 0;
        boolean deferred = false;
        String failure = null;
        for (LocalDate day : missing == null ? List.<LocalDate>of() : missing) {
            try {
                if (!Boolean.TRUE.equals(transactions.execute(status -> backfillCfBaselineDay(day)))) {
                    problems.add("advisory lock held; CF baseline backfill stopped after " + processed + " day(s)");
                    return false;
                }
                processed++;
                consecutive = 0;
            } catch (RuntimeException e) {
                log.warn("[{}] CF baseline backfill day={} failed: {}", NAME, day, e.getClass().getSimpleName());
                failed++;
                failedDays.add(day);
                if (failure == null) {
                    failure = e.getClass().getSimpleName();
                }
                if (++consecutive >= MAX_CONSECUTIVE_CF_BASELINE_FAILURES) {
                    deferred = true;
                    break;
                }
            }
        }
        if (processed > 0 || failed > 0) {
            log.info("[{}] CF baseline backfill processed={} failed={}", NAME, processed, failed);
        }
        if (failed > 0) {
            problems.add("CF baseline backfill: " + failed + " day(s) failed (" + failure + ")"
                    + (deferred ? ", remaining days deferred" : ""));
        }
        return true;
    }

    /**
     * Replaces the baseline of every rolled day ({@link #replaceCfBaselineDay}), except the days whose backfill just
     * failed in this run (they are still missing, so the next run's backfill retries them). Stops at a held lock and
     * after {@link #MAX_CONSECUTIVE_CF_BASELINE_FAILURES} failed days in a row.
     */
    void refreshCfBaseline(List<LocalDate> rolled, Set<LocalDate> skip, List<String> problems) {
        int failed = 0;
        int consecutive = 0;
        boolean deferred = false;
        String failure = null;
        for (LocalDate day : rolled) {
            if (skip.contains(day)) {
                continue;
            }
            try {
                if (!Boolean.TRUE.equals(transactions.execute(status -> replaceCfBaselineDay(day)))) {
                    problems.add("advisory lock held; CF baseline not refreshed");
                    break;
                }
                consecutive = 0;
            } catch (RuntimeException e) {
                log.warn("[{}] CF baseline day={} failed: {}", NAME, day, e.getClass().getSimpleName());
                failed++;
                if (failure == null) {
                    failure = e.getClass().getSimpleName();
                }
                if (++consecutive >= MAX_CONSECUTIVE_CF_BASELINE_FAILURES) {
                    deferred = true;
                    break;
                }
            }
        }
        if (failed > 0) {
            problems.add("CF baseline refresh: " + failed + " day(s) failed (" + failure + ")"
                    + (deferred ? ", remaining days deferred" : ""));
        }
    }

    /** The backfill range ({@link #backfillCfBaseline}) minus the days that already have baseline rows, oldest first. */
    List<LocalDate> missingCfBaselineDays() {
        repository.applyDaySettings();
        Instant firstHour = repository.firstNetmonHour();
        if (firstHour == null) {
            return List.of();
        }
        LocalDate first = days.dayOf(firstHour).plusDays(1);
        LocalDate last = days.dayOf(clock.instant()).minusDays(1);
        if (first.isAfter(last)) {
            return List.of();
        }
        Set<LocalDate> stored = new HashSet<>(repository.cfBaselineDays(first, last));
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (!stored.contains(d)) {
                missing.add(d);
            }
        }
        return missing;
    }

    /**
     * Backfills one day: takes the advisory lock, then checks in the same transaction that the day still has no
     * baseline row (a concurrent writer may have filled it), then writes netmon's rows for it.
     */
    boolean backfillCfBaselineDay(LocalDate day) {
        if (!repository.tryLock()) {
            return false;
        }
        repository.applyDaySettings();
        if (repository.cfBaselineHasDay(day)) {
            return true;
        }
        repository.replaceCfBaseline(day, day, days.start(day), days.start(day.plusDays(1)), days.zone().getId());
        return true;
    }

    /**
     * Nightly replace of one day's baseline. Skipped for days older than netmon's retention (their source rows are
     * gone or cut) and when netmon yields no row for a day that already has baseline rows, so stored history is never
     * wiped by a later run.
     */
    boolean replaceCfBaselineDay(LocalDate day) {
        if (!repository.tryLock()) {
            return false;
        }
        repository.applyDaySettings();
        LocalDate oldestComplete = days.dayOf(clock.instant()).minusDays(netmonRetentionDays - 1L);
        if (day.isBefore(oldestComplete)) {
            return true;
        }
        Instant from = days.start(day);
        Instant to = days.start(day.plusDays(1));
        String zone = days.zone().getId();
        if (repository.cfBaselineRowsAvailable(from, to, zone) == 0 && repository.cfBaselineHasDay(day)) {
            return true;
        }
        repository.replaceCfBaseline(day, day, from, to, zone);
        return true;
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
        repository.replaceDay(day, days.start(day), days.start(day.plusDays(1)), computed.rows(), visitors, new RollupDayRow(day, raw[0], raw[1],
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
        Set<UUID> earlier = repository.visitorsSeenBefore(from, to);

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
                    boolean isNew = !earlier.contains(entry.getKey())
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
