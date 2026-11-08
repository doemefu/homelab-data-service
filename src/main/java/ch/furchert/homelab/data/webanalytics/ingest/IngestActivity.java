package ch.furchert.homelab.data.webanalytics.ingest;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * In-memory ingest activity for {@code /api/webanalytics/status.ingest} (docs/100 §8.2): {@code eventsToday} =
 * stored cap-counted events (pageview, engagement, click; goal and enquiry are cap-exempt, §6.4) with
 * {@code received_at} in the current UTC day, and {@code lastEventAt} = the newest {@code received_at} of any stored
 * event. Updated after each batch commits; reset lazily when the UTC day changes; seeded once at startup from the
 * database plus the in-memory delta recorded while the seed query ran. The same counter backs the WA-5
 * {@code webanalytics_events_today} gauge.
 */
@Component
public class IngestActivity {

    /** A consistent read of the two values. */
    public record Snapshot(Instant lastEventAt, long eventsToday) {
    }

    private final Clock clock;
    private LocalDate utcDay;
    private long eventsToday;
    private Instant lastEventAt;
    private boolean seeding;
    private long deltaSinceSeedStart;

    public IngestActivity(Clock clock) {
        this.clock = clock;
        this.utcDay = today();
    }

    /**
     * @param capCountedReceivedAt {@code received_at} of the stored pageview, engagement and click events
     * @param newestReceivedAt     newest {@code received_at} of every stored event (goals included), or null
     */
    public synchronized void recordStored(Collection<Instant> capCountedReceivedAt, Instant newestReceivedAt) {
        rollDay();
        for (Instant receivedAt : capCountedReceivedAt) {
            if (LocalDate.ofInstant(receivedAt, ZoneOffset.UTC).equals(utcDay)) {
                eventsToday++;
                if (seeding) {
                    deltaSinceSeedStart++;
                }
            }
        }
        newest(newestReceivedAt);
    }

    /** Called right before the seed query runs. */
    public synchronized void seedStarted() {
        seeding = true;
        deltaSinceSeedStart = 0;
    }

    /** @param day the UTC day the database count belongs to */
    public synchronized void seedCompleted(LocalDate day, long storedToday, Instant newestStored) {
        rollDay();
        if (day.equals(utcDay)) {
            eventsToday = storedToday + deltaSinceSeedStart;
        }
        newest(newestStored);
        seeding = false;
    }

    public synchronized Snapshot snapshot() {
        rollDay();
        return new Snapshot(lastEventAt, eventsToday);
    }

    private void rollDay() {
        LocalDate today = today();
        if (!today.equals(utcDay)) {
            utcDay = today;
            eventsToday = 0;
        }
    }

    private void newest(Instant candidate) {
        if (candidate != null && (lastEventAt == null || candidate.isAfter(lastEventAt))) {
            lastEventAt = candidate;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
