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
 * passes {@link #eligible}. Without any rolled day the run writes yesterday only. An already-final day is not
 * rewritten: its events cannot change after the close.
 */
public final class RollupPlanner {

    public static final int DEFINITION_VERSION = 1;
    static final int MAX_DAYS_PER_CATEGORY = 31;
    static final int VERSION_HORIZON_DAYS = 30;

    /** One {@code rollup_day} row as far as planning is concerned. */
    public record RolledDay(LocalDate day, boolean fin, int definitionVersion) {
    }

    private final ZurichDays days;

    public RollupPlanner(ZurichDays days) {
        this.days = days;
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
        for (LocalDate d = first; !d.isAfter(yesterday) && missing < MAX_DAYS_PER_CATEGORY; d = d.plusDays(1)) {
            if (!byDay.containsKey(d)) {
                candidates.add(d);
                missing++;
            }
        }
        rolled.stream()
                .filter(r -> !r.fin() && days.isClosed(r.day(), now))
                .map(RolledDay::day)
                .sorted()
                .limit(MAX_DAYS_PER_CATEGORY)
                .forEach(candidates::add);
        rolled.stream()
                .filter(r -> r.definitionVersion() < DEFINITION_VERSION
                        && !r.day().isBefore(today.minusDays(VERSION_HORIZON_DAYS)))
                .map(RolledDay::day)
                .sorted(Comparator.reverseOrder())
                .limit(MAX_DAYS_PER_CATEGORY)
                .forEach(candidates::add);
        return candidates.stream().filter(d -> eligible(d, now, first)).toList();
    }

    /**
     * The single eligibility rule for every selection path (docs/100 §7.5): before today and not before the first
     * rolled day. WA-5 adds the freeze guard (retention bound, 30-day recompute horizon, purged days) here.
     */
    public boolean eligible(LocalDate day, Instant now, LocalDate firstRolled) {
        return day.isBefore(days.dayOf(now)) && (firstRolled == null || !day.isBefore(firstRolled));
    }
}
