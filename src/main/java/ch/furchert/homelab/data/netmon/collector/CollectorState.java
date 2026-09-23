package ch.furchert.homelab.data.netmon.collector;

import java.time.Instant;

/** One row of {@code netmon.collector_state}. */
public record CollectorState(
        String collector,
        Instant lastWindowEnd,
        String cursor,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        int consecutiveFailures,
        String lastError,
        String lastErrorCode) {
}
