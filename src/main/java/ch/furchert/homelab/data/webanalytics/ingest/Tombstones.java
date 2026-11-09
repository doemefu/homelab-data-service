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
 * In-memory erasure tombstones (docs/100 §6.5): an erased visitor id stays here for 2 h, and events for it are rejected
 * ({@code erased}). Hard cap of 10 000 ids, oldest evicted, so many erasures with random ids cannot grow memory without
 * bound (#56). A repeated erasure refreshes the entry. Lost on restart and per pod (accepted by the spec: the
 * furchert-ch queue purge covers the normal case).
 */
@Component
public class Tombstones {

    static final Duration TTL = Duration.ofHours(2);
    static final int MAX_ENTRIES = 10_000;

    private final Clock clock;
    /** Insertion-ordered, so the oldest entry is always first. */
    private final LinkedHashMap<UUID, Instant> entries = new LinkedHashMap<>();

    public Tombstones(Clock clock) {
        this.clock = clock;
    }

    public synchronized void add(UUID visitorId) {
        Instant now = clock.instant();
        prune(now);
        entries.remove(visitorId);
        if (entries.size() >= MAX_ENTRIES) {
            Iterator<UUID> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        entries.put(visitorId, now);
    }

    public synchronized boolean contains(UUID visitorId) {
        prune(clock.instant());
        return entries.containsKey(visitorId);
    }

    synchronized int size() {
        return entries.size();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(TTL);
        Iterator<Map.Entry<UUID, Instant>> it = entries.entrySet().iterator();
        while (it.hasNext() && !it.next().getValue().isAfter(cutoff)) {
            it.remove();
        }
    }
}
