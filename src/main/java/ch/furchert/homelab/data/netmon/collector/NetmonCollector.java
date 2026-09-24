package ch.furchert.homelab.data.netmon.collector;

import java.time.Duration;

/**
 * One scheduled data-set collector (docs/060 §4.1). Implementations own their {@code @Scheduled}
 * trigger and hand themselves to {@link CollectorRunner#run(NetmonCollector)}, which applies the
 * kill switch, the no-overlap lock and the collector_state bookkeeping.
 */
public interface NetmonCollector {

    /** The {@code netmon.collector_state.collector} key, e.g. {@code retention}. */
    String name();

    /** Nominal interval between runs; {@code stale} in the status API is 3 x this value. */
    Duration cadence();

    /**
     * {@code false} when the collector lacks required configuration and must not run at all (e.g.
     * {@code reputation} without an AbuseIPDB key, docs/060 §4.5). Such a collector is treated like one
     * whose kill switch is off: it is skipped and reported as {@code enabled=false}, never as stale.
     */
    default boolean available() {
        return true;
    }

    /**
     * Whether {@code netmon_collector_last_success_timestamp_seconds} is registered at startup. {@code false}
     * for a collector that runs but cannot succeed with the configuration it started with (e.g. Cloudflare
     * without token/zone id): it still reports {@code credentials} in {@code /status}, but a permanently NaN
     * gauge would make {@code NetmonCollectorStale} fire forever. Env vars only change with a restart, so the
     * startup decision holds for the pod's lifetime.
     */
    default boolean exportsFreshnessGauge() {
        return true;
    }

    /**
     * Whether a failure with this {@link ErrorCode} spaces the next runs by the runner's exponential backoff.
     * Default: {@code rate_limited} and {@code upstream}. A collector may add codes (e.g. {@code login-events} adds
     * {@code credentials}, so a wrong secret does not hit the IdP every minute).
     */
    default boolean backsOffAfter(ErrorCode code) {
        return code == ErrorCode.RATE_LIMITED || code == ErrorCode.UPSTREAM;
    }

    /**
     * Performs one run. Throw {@link CollectorException} with a safe, self-authored message for
     * expected failures; any other exception is recorded as {@code internal} by class name only.
     */
    void collect() throws Exception;
}
