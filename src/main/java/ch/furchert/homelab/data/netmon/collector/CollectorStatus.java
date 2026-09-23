package ch.furchert.homelab.data.netmon.collector;

import java.time.Instant;

/** One element of {@code GET /api/netmon/status} (docs/060 §7.2). */
public record CollectorStatus(
        String name,
        boolean enabled,
        Instant lastSuccessAt,
        Instant lastWindowEnd,
        int consecutiveFailures,
        String lastErrorCode,
        boolean stale) {
}
