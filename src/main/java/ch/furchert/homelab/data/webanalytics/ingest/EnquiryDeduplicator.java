package ch.furchert.homelab.data.webanalytics.ingest;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory 2 h dedupe of enquiry {@code eventId}s (docs/100 §6.2), so a forward-queue retry never counts an
 * enquiry twice. No id is stored anywhere else. Lost on restart and valid for one replica only (accepted by the
 * spec). The size cap only guards memory; enquiries are bounded by the contact form's own limits.
 */
@Component
public class EnquiryDeduplicator {

    static final Duration WINDOW = Duration.ofHours(2);
    static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    /** Insertion-ordered, so the oldest reservation is always first. */
    private final LinkedHashMap<UUID, Instant> reserved = new LinkedHashMap<>();

    public EnquiryDeduplicator(Clock clock) {
        this.clock = clock;
    }

    /** Reserves the id; false if it was reserved within the last 2 h. */
    public synchronized boolean reserve(UUID eventId) {
        Instant now = clock.instant();
        prune(now);
        if (reserved.containsKey(eventId)) {
            return false;
        }
        if (reserved.size() >= MAX_ENTRIES) {
            Iterator<UUID> oldest = reserved.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        reserved.put(eventId, now);
        return true;
    }

    /** Releases a reservation whose transaction rolled back, so the retry is counted. */
    public synchronized void release(UUID eventId) {
        reserved.remove(eventId);
    }

    synchronized int size() {
        return reserved.size();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        Iterator<Map.Entry<UUID, Instant>> entries = reserved.entrySet().iterator();
        while (entries.hasNext() && !entries.next().getValue().isAfter(cutoff)) {
            entries.remove();
        }
    }
}
