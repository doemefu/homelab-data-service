package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills and repairs {@code egress_flow_daily} per UTC day (data-service#57; docs/060 §3.3 as amended): the backfill after
 * the rollout and the roll-forward repair after a rollback. Runs at the start of every egress collector run, so it never
 * blocks readiness. Range {@code [max(day of the oldest hourly row, today − (dailyDays − 1)), today]}, newest day first,
 * at most {@code maxDays} recomputes per run, each in its own transaction:
 * <ul>
 *   <li>a day with hourly rows but no daily rows is recomputed;</li>
 *   <li>a day whose hourly rows were written after its daily rows is recomputed: on the first run after startup for the
 *       whole range, afterwards only for the last 3 days.</li>
 * </ul>
 * A day whose hourly rows are gone keeps its daily rows. Logs counts and days only.
 */
@Component
public class EgressDailyRepair {

    static final int STALE_DAYS = 3;
    private static final Logger log = LoggerFactory.getLogger(EgressDailyRepair.class);

    /** Outcome of one repair pass. */
    record Result(int recomputed, int failed) {
    }

    private final EgressSnapshotRepository repository;
    private final Clock clock;
    private final int dailyDays;
    private final int maxDays;
    private volatile boolean fullCheckDone;

    @Autowired
    public EgressDailyRepair(EgressSnapshotRepository repository, RetentionProperties retention, Clock clock) {
        this(repository, retention, clock, retention.egressFlowDailyDays());
    }

    /** For tests: a smaller per-run limit. */
    EgressDailyRepair(EgressSnapshotRepository repository, RetentionProperties retention, Clock clock, int maxDays) {
        this.repository = repository;
        this.clock = clock;
        this.dailyDays = retention.egressFlowDailyDays();
        this.maxDays = maxDays;
    }

    Result repair() {
        Instant oldest = repository.oldestWindow();
        if (oldest == null) {
            fullCheckDone = true;
            return new Result(0, 0);
        }
        LocalDate today = EgressSnapshotRepository.utcDay(clock.instant());
        LocalDate from = later(EgressSnapshotRepository.utcDay(oldest), today.minusDays(dailyDays - 1L));
        LocalDate staleFrom = fullCheckDone ? today.minusDays(STALE_DAYS - 1L) : from;
        List<LocalDate> due = new ArrayList<>();
        for (LocalDate day = today; !day.isBefore(from); day = day.minusDays(1)) {
            if (!repository.hasHourlyRows(day)) {
                continue;
            }
            if (!repository.hasDailyRows(day) || (!day.isBefore(staleFrom) && repository.isStale(day))) {
                due.add(day);
            }
        }
        int recomputed = 0;
        int failed = 0;
        for (LocalDate day : due) {
            if (recomputed + failed == maxDays) {
                break;
            }
            try {
                repository.recomputeDay(day);
                recomputed++;
            } catch (DataAccessException e) {
                failed++;
                log.warn("[{}] daily recompute day={} failed: {}", EgressCollector.NAME, day,
                        e.getClass().getSimpleName());
            }
        }
        if (failed == 0 && due.size() <= maxDays) {
            fullCheckDone = true;
        }
        if (recomputed > 0 || failed > 0) {
            log.info("[{}] daily repair recomputed={} failed={} due={}", EgressCollector.NAME, recomputed, failed,
                    due.size());
        }
        return new Result(recomputed, failed);
    }

    /** Whether a UTC day in {@code [from, toExclusive)} has hourly rows but no daily rows. */
    boolean anyDayMissing(LocalDate from, LocalDate toExclusive) {
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            if (repository.hasHourlyRows(day) && !repository.hasDailyRows(day)) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
