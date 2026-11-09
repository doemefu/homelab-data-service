package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which days a rollup run writes (docs/100 §7.5), derived from the clock and {@code rollup_day} only, so the 01:12 and
 * 07:30 triggers share one rule. Candidates: days missing in [first rolled day, yesterday] (gap scan, oldest first),
 * rolled days still {@code final = false} that are closed (oldest first), and rolled days with an outdated
 * {@code definition_version} within 30 days (newest first); each category at most 31 days per run. Every candidate
 * passes {@link #eligible} (incl. the WA-5 freeze guard (a) to (c); (d) is checked in the day transaction). Without any
 * rolled day the run writes yesterday only. An already-final day is not rewritten: its events cannot change after the
 * close.
 */
public final class RollupPlanner {

    /** 2 since WA-3b (journey, audience, baseline and submit tables); older days keep 1 (docs/100 §8.5, rev 3.5). */
    public static final int DEFINITION_VERSION = 2;
    static final int MAX_DAYS_PER_CATEGORY = 31;
    static final int VERSION_HORIZON_DAYS = 30;
    /** Freeze guard (c): a day that already has a {@code rollup_day} row is recomputed only within this horizon. */
    static final int RECOMPUTE_HORIZON_DAYS = 30;

    /** One {@code rollup_day} row as far as planning is concerned. */
    public record RolledDay(LocalDate day, boolean fin, int definitionVersion) {
    }

    private final ZurichDays days;
    private final int retentionDays;

    /** With the shipped raw retention of 396 days. */
    public RollupPlanner(ZurichDays days) {
        this(days, ch.furchert.homelab.data.config.WebAnalyticsProperties.Retention.DEFAULT_EVENTS_DAYS);
    }

    /** @param retentionDays the configured {@code webanalytics.retention.events-days} (freeze guard (b)) */
    public RollupPlanner(ZurichDays days, int retentionDays) {
        this.days = days;
        this.retentionDays = retentionDays;
    }

    public List<LocalDate> plan(Instant now, Collection<RolledDay> rolled) {
        LocalDate today = days.dayOf(now);
        LocalDate yesterday = today.minusDays(1);
        if (rolled.isEmpty()) {
            return List.of(yesterday);
        }
        Map<LocalDate, RolledDay> byDay = rolled.stream()
                .collect(Collectors.toMap(RolledDay::day, Function.identity()));
        LocalDate first = byDay.keySet().stream().min(Comparator.naturalOrder()).orElseThrow();

        Set<LocalDate> candidates = new LinkedHashSet<>();
        int missing = 0;
        // The gap scan starts at the retention bound, so refused days never use up the per-run budget.
        LocalDate retained = oldestRetained(today);
        LocalDate scanFrom = first.isBefore(retained) ? retained : first;
        for (LocalDate d = scanFrom; !d.isAfter(yesterday) && missing < MAX_DAYS_PER_CATEGORY; d = d.plusDays(1)) {
            if (!byDay.containsKey(d)) {
                candidates.add(d);
                missing++;
            }
        }
        rolled.stream()
                .filter(r -> !r.fin() && days.isClosed(r.day(), now))
                .filter(r -> eligible(r.day(), now, first, r))
                .map(RolledDay::day)
                .sorted()
                .limit(MAX_DAYS_PER_CATEGORY)
                .forEach(candidates::add);
        rolled.stream()
                .filter(r -> r.definitionVersion() < DEFINITION_VERSION
                        && !r.day().isBefore(today.minusDays(VERSION_HORIZON_DAYS)))
                .filter(r -> eligible(r.day(), now, first, r))
                .map(RolledDay::day)
                .sorted(Comparator.reverseOrder())
                .limit(MAX_DAYS_PER_CATEGORY)
                .forEach(candidates::add);
        return candidates.stream().filter(d -> eligible(d, now, first, byDay.get(d))).toList();
    }

    /**
     * The single eligibility rule for every selection path (docs/100 §7.5): not before the first rolled day, plus the
     * WA-5 freeze guard (a) {@code day < today}; (b) {@code day >= today − (retentionDays − 1)}, so a day whose raw
     * events retention has started to delete is never recomputed; (c) {@code day >= today − 30} or no
     * {@code rollup_day} row yet. Condition (d) (a final day whose raw events are gone) needs the database and is
     * checked in the day transaction.
     *
     * @param rolled the day's {@code rollup_day} row, or null when there is none
     */
    public boolean eligible(LocalDate day, Instant now, LocalDate firstRolled, RolledDay rolled) {
        LocalDate today = days.dayOf(now);
        return day.isBefore(today)
                && !day.isBefore(oldestRetained(today))
                && (rolled == null || !day.isBefore(today.minusDays(RECOMPUTE_HORIZON_DAYS)))
                && (firstRolled == null || !day.isBefore(firstRolled));
    }

    /** Freeze guard (b): the oldest day whose raw events are all still retained. */
    public LocalDate oldestRetained(LocalDate today) {
        return today.minusDays(retentionDays - 1L);
    }
}
