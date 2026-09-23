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
     * Performs one run. Throw {@link CollectorException} with a safe, self-authored message for
     * expected failures; any other exception is recorded as {@code internal} by class name only.
     */
    void collect() throws Exception;
}
