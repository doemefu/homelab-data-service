package ch.furchert.homelab.data.webanalytics.ingest;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * In-memory ingest activity for {@code /api/webanalytics/status.ingest} (docs/100 §8.2): {@code lastEventAt} = the
 * newest {@code received_at} of any stored event (updated after each batch commits, seeded once at startup), and
 * {@code eventsToday} read from {@link IngestCaps}, the single owner of the cap counts (the same number backs the
 * daily cap and the {@code webanalytics_events_today} gauge).
 */
@Component
public class IngestActivity {

    /** A consistent read of the two values. */
    public record Snapshot(Instant lastEventAt, long eventsToday) {
    }

    private final IngestCaps caps;
    private Instant lastEventAt;

    public IngestActivity(IngestCaps caps) {
        this.caps = caps;
    }

    /** @param newestReceivedAt newest {@code received_at} of the stored events (goals included), or null */
    public synchronized void recordNewest(Instant newestReceivedAt) {
        if (newestReceivedAt != null && (lastEventAt == null || newestReceivedAt.isAfter(lastEventAt))) {
            lastEventAt = newestReceivedAt;
        }
    }

    public Snapshot snapshot() {
        long today = caps.eventsToday();
        synchronized (this) {
            return new Snapshot(lastEventAt, today);
        }
    }
}
