package ch.furchert.homelab.data.netmon.collector;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code netmon_collector_last_success_timestamp_seconds{collector}} (docs/060 §4.1): unix time of a
 * collector's last successful run, NaN until the first success. Values are kept in memory so a
 * Prometheus scrape never touches the database.
 */
@Component
public class CollectorMetrics {

    static final String GAUGE = "netmon.collector.last.success.timestamp";

    private final MeterRegistry registry;
    private final Map<String, Double> lastSuccessSeconds = new ConcurrentHashMap<>();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Registers the gauge for a collector (idempotent), seeding it with a persisted success time. */
    public void register(String collector, Instant lastSuccess) {
        if (lastSuccess != null) {
            lastSuccessSeconds.merge(collector, (double) lastSuccess.getEpochSecond(), Math::max);
        }
        if (!registered.add(collector)) {
            return;
        }
        Gauge.builder(GAUGE, lastSuccessSeconds, values -> values.getOrDefault(collector, Double.NaN))
                .description("Unix time of the collector's last successful run")
                .baseUnit("seconds")
                .tag("collector", collector)
                .register(registry);
    }

    public void markSuccess(String collector, Instant at) {
        lastSuccessSeconds.put(collector, (double) at.getEpochSecond());
        register(collector, null);
    }
}
