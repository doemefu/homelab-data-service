package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The single owner of the WA-5 cap counts (docs/100 §6.4): accepted analytics events (pageview, engagement, click)
 * per UTC hour of {@code received_at} (the UTC day is the sum of its hours), per visitor id for the current UTC day,
 * and the capped state ({@code cappedUntil}). Everything happens under one monitor, so a check and its reservation are
 * atomic and all-or-nothing holds for concurrent batches: {@link #reserve} checks and reserves, {@link #commit}
 * charges the rows actually stored after the batch committed, {@link #release} drops a rolled-back reservation.
 *
 * <p>Each event is charged to the window of its own {@code received_at}; only the current UTC hour and day are
 * enforced (an ended window cannot be paused any more). The day cap is checked before the hour cap. A batch without
 * analytics events is never capped. Per-visitor keys hold counts of the current UTC day only (cleared at 00:00 UTC);
 * an event received on the previous day is not checked against them. The map is bounded at twice the daily cap,
 * oldest key evicted, and a key stays when all its events were rejected (it caches "at cap").
 *
 * <p>Seeded once at startup from {@code events.received_at} ({@link IngestActivitySeeder}); batches committed while
 * the seed query ran are added on top. Lost on restart and per pod (replicas 1). Holds visitor ids in memory only.
 */
@Component
public class IngestCaps {

    /** The window a capped batch hit, with its wire label ({@code capScope}). */
    public enum Scope {
        DAY("day"), HOUR("hour");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A capped batch: which window, when it opens again, how many analytics events the batch wanted to store. */
    public record Capped(Scope scope, Instant retryAt, int events) {
    }

    /** What a successful check reserved; handed back to {@link #commit} or {@link #release}. */
    public static final class Reservation {
        private final Map<Instant, Long> hours;
        private final Map<UUID, Integer> visitors;
        private final LocalDate visitorDay;

        private Reservation(Map<Instant, Long> hours, Map<UUID, Integer> visitors, LocalDate visitorDay) {
            this.hours = hours;
            this.visitors = visitors;
            this.visitorDay = visitorDay;
        }
    }

    /**
     * Result of {@link #reserve}: either {@code capped} (nothing reserved) or a {@code reservation}; in both cases the
     * ids of the events rejected by the per-visitor cap.
     */
    public record Decision(Capped capped, Reservation reservation, Set<UUID> visitorCapped) {
    }

    private static final DateTimeFormatter RETRY_AT = DateTimeFormatter.ISO_INSTANT;

    private final Clock clock;
    private final int dailyCap;
    private final int hourlyCap;
    private final int visitorDailyCap;
    private final int maxVisitorKeys;

    private final TreeMap<Instant, Long> committed = new TreeMap<>();
    private final TreeMap<Instant, Long> reserved = new TreeMap<>();
    private final LinkedHashMap<UUID, Integer> visitorCommitted = new LinkedHashMap<>();
    private final Map<UUID, Integer> visitorReserved = new HashMap<>();
    private LocalDate visitorDay;
    private Instant cappedUntil = Instant.EPOCH;
    private boolean seeding;
    private final TreeMap<Instant, Long> seedDelta = new TreeMap<>();

    public IngestCaps(Clock clock, WebAnalyticsProperties properties) {
        this.clock = clock;
        this.dailyCap = properties.ingest().dailyCap();
        this.hourlyCap = properties.ingest().hourlyCap();
        this.visitorDailyCap = properties.ingest().visitorDailyCap();
        this.maxVisitorKeys = (int) Math.max(1, Math.min(Integer.MAX_VALUE, 2L * dailyCap));
        this.visitorDay = utcDay(clock.instant());
    }

    public int dailyCap() {
        return dailyCap;
    }

    /** The visitor ids without a per-visitor count for the current UTC day (to be seeded from the database). */
    public synchronized Set<UUID> unseenVisitors(Collection<UUID> visitorIds) {
        roll(clock.instant());
        Set<UUID> unseen = new LinkedHashSet<>();
        for (UUID id : visitorIds) {
            if (id != null && !visitorCommitted.containsKey(id)) {
                unseen.add(id);
            }
        }
        return unseen;
    }

    /**
     * Checks a batch's analytics events (goal and enquiry never reach here) and reserves them.
     *
     * @param analytics the batch's pageview/engagement/click events that would be stored, in batch order
     * @param seeds     stored counts of the current UTC day for visitors from {@link #unseenVisitors}
     */
    public synchronized Decision reserve(List<ValidEvent> analytics, Map<UUID, Long> seeds) {
        Instant now = clock.instant();
        roll(now);
        LocalDate today = utcDay(now);
        seeds.forEach((visitor, count) -> {
            if (!visitorCommitted.containsKey(visitor)) {
                putVisitor(visitor, (int) Math.min(Integer.MAX_VALUE, count));
            }
        });

        Set<UUID> visitorCapped = new LinkedHashSet<>();
        Map<UUID, Integer> plannedVisitors = new HashMap<>();
        List<ValidEvent> kept = new ArrayList<>(analytics.size());
        for (ValidEvent event : analytics) {
            UUID visitor = event.visitorId();
            if (visitor != null && utcDay(event.receivedAt()).equals(today)) {
                long used = (long) visitorCommitted.getOrDefault(visitor, 0) + visitorReserved.getOrDefault(visitor, 0)
                        + plannedVisitors.getOrDefault(visitor, 0);
                if (used >= visitorDailyCap) {
                    visitorCapped.add(event.eventId());
                    if (!visitorCommitted.containsKey(visitor)) {
                        putVisitor(visitor, 0);
                    }
                    continue;
                }
                plannedVisitors.merge(visitor, 1, Integer::sum);
            }
            kept.add(event);
        }

        Map<Instant, Long> plannedHours = new TreeMap<>();
        for (ValidEvent event : kept) {
            plannedHours.merge(hourOf(event.receivedAt()), 1L, Long::sum);
        }
        Instant currentHour = hourOf(now);
        long dayAdd = plannedHours.entrySet().stream().filter(e -> utcDay(e.getKey()).equals(today))
                .mapToLong(Map.Entry::getValue).sum();
        long hourAdd = plannedHours.getOrDefault(currentHour, 0L);
        Capped capped = null;
        if (dayAdd > 0 && dayCount(today, true) + dayAdd > dailyCap) {
            capped = new Capped(Scope.DAY, today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), kept.size());
        } else if (hourAdd > 0 && hourCount(currentHour, true) + hourAdd > hourlyCap) {
            capped = new Capped(Scope.HOUR, currentHour.plus(Duration.ofHours(1)), kept.size());
        }
        if (capped != null) {
            if (capped.retryAt().isAfter(cappedUntil)) {
                cappedUntil = capped.retryAt();
            }
            return new Decision(capped, null, visitorCapped);
        }
        plannedHours.forEach((hour, n) -> reserved.merge(hour, n, Long::sum));
        plannedVisitors.forEach((visitor, n) -> visitorReserved.merge(visitor, n, Integer::sum));
        return new Decision(null, new Reservation(plannedHours, plannedVisitors, today), visitorCapped);
    }

    /** After commit: drops the reservation and charges the analytics events actually stored. */
    public synchronized void commit(Reservation reservation, Collection<ValidEvent> stored) {
        Instant now = clock.instant();
        roll(now);
        if (reservation != null) {
            unreserve(reservation);
        }
        LocalDate today = utcDay(now);
        for (ValidEvent event : stored) {
            Instant hour = hourOf(event.receivedAt());
            committed.merge(hour, 1L, Long::sum);
            if (seeding) {
                seedDelta.merge(hour, 1L, Long::sum);
            }
            UUID visitor = event.visitorId();
            if (visitor != null && utcDay(event.receivedAt()).equals(today)) {
                Integer current = visitorCommitted.get(visitor);
                if (current == null) {
                    putVisitor(visitor, 1);
                } else {
                    visitorCommitted.put(visitor, current + 1);
                }
            }
        }
    }

    /** After rollback: drops the reservation. */
    public synchronized void release(Reservation reservation) {
        roll(clock.instant());
        if (reservation != null) {
            unreserve(reservation);
        }
    }

    /** Drops a visitor's per-visitor count (erasure); it is seeded again from the database on next sight. */
    public synchronized void forgetVisitor(UUID visitorId) {
        visitorCommitted.remove(visitorId);
        visitorReserved.remove(visitorId);
    }

    /** {@code webanalytics_ingest_capped}: true from a capped batch until its {@code retryAt}, evaluated on read. */
    public synchronized boolean capped() {
        return clock.instant().isBefore(cappedUntil);
    }

    /** Accepted analytics events with {@code received_at} in the current UTC day ({@code eventsToday}). */
    public synchronized long eventsToday() {
        Instant now = clock.instant();
        roll(now);
        return dayCount(utcDay(now), false);
    }

    /** Called right before the seed query runs. */
    public synchronized void seedStarted() {
        seeding = true;
        seedDelta.clear();
    }

    /**
     * Replaces the committed counts with the database counts plus the batches committed since {@link #seedStarted}.
     * At startup nothing is committed before the query, so this is exact. During a live re-seed (5 minutes after a
     * failed seed, with traffic) a batch that commits after {@code seedStarted} but before the query's snapshot is
     * counted twice (in the query result and in the delta): the error only ever overcounts, i.e. errs toward capping
     * early, and disappears at the next window or restart.
     *
     * @param hourCounts stored analytics events per UTC hour start of {@code received_at}, from the start of the UTC
     *                   day of {@code now − 2 h}
     */
    public synchronized void seedCompleted(Map<Instant, Long> hourCounts) {
        committed.clear();
        committed.putAll(hourCounts);
        seedDelta.forEach((hour, n) -> committed.merge(hour, n, Long::sum));
        seedDelta.clear();
        visitorCommitted.clear();
        seeding = false;
        Instant now = clock.instant();
        roll(now);
        LocalDate today = utcDay(now);
        Instant currentHour = hourOf(now);
        Instant restored = null;
        if (dayCount(today, false) >= dailyCap) {
            restored = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else if (hourCount(currentHour, false) >= hourlyCap) {
            restored = currentHour.plus(Duration.ofHours(1));
        }
        if (restored != null && restored.isAfter(cappedUntil)) {
            cappedUntil = restored;
        }
    }

    /** The seed query failed: keep counting from startup. */
    public synchronized void seedFailed() {
        seeding = false;
        seedDelta.clear();
    }

    synchronized int visitorKeys() {
        return visitorCommitted.size();
    }

    /** {@code retryAt} on the wire: ISO-8601 UTC, whole seconds, ending in {@code Z}. */
    public static String format(Instant retryAt) {
        return RETRY_AT.format(retryAt.truncatedTo(ChronoUnit.SECONDS));
    }

    private void roll(Instant now) {
        LocalDate today = utcDay(now);
        if (!today.equals(visitorDay)) {
            visitorDay = today;
            visitorCommitted.clear();
            visitorReserved.clear();
        }
        Instant keepFrom = today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        committed.headMap(keepFrom).clear();
        reserved.headMap(keepFrom).clear();
    }

    private void unreserve(Reservation reservation) {
        reservation.hours.forEach((hour, n) -> reserved.computeIfPresent(hour, (k, v) -> v - n > 0 ? v - n : null));
        if (reservation.visitorDay.equals(visitorDay)) {
            reservation.visitors.forEach((visitor, n) ->
                    visitorReserved.computeIfPresent(visitor, (k, v) -> v - n > 0 ? v - n : null));
        }
    }

    private void putVisitor(UUID visitor, int count) {
        while (visitorCommitted.size() >= maxVisitorKeys) {
            Iterator<UUID> oldest = visitorCommitted.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        visitorCommitted.put(visitor, count);
    }

    private long dayCount(LocalDate day, boolean withReserved) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long sum = committed.subMap(from, to).values().stream().mapToLong(Long::longValue).sum();
        if (withReserved) {
            sum += reserved.subMap(from, to).values().stream().mapToLong(Long::longValue).sum();
        }
        return sum;
    }

    private long hourCount(Instant hour, boolean withReserved) {
        return committed.getOrDefault(hour, 0L) + (withReserved ? reserved.getOrDefault(hour, 0L) : 0L);
    }

    private static Instant hourOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }

    private static LocalDate utcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }
}
