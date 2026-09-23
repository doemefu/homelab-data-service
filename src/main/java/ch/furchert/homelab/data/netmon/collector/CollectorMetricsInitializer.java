package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registers the freshness gauge for every collector bean once the context is ready, seeded from
 * {@code collector_state}. Resolving the collectors lazily avoids a construction cycle
 * (collector -> runner -> metrics -> collectors). A collector that cannot run (kill switch off, or
 * {@link NetmonCollector#available()} false, e.g. {@code reputation} without a key) gets no gauge: a
 * permanent NaN would make the NaN-aware {@code NetmonCollectorStale} rule fire for a collector that is
 * off on purpose.
 */
@Component
class CollectorMetricsInitializer {

    private final ObjectProvider<NetmonCollector> collectors;
    private final CollectorStateRepository repository;
    private final CollectorMetrics metrics;
    private final NetmonProperties properties;

    CollectorMetricsInitializer(ObjectProvider<NetmonCollector> collectors,
                                CollectorStateRepository repository,
                                CollectorMetrics metrics,
                                NetmonProperties properties) {
        this.collectors = collectors;
        this.repository = repository;
        this.metrics = metrics;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerGauges() {
        Map<String, CollectorState> states = repository.findAll().stream()
                .collect(Collectors.toMap(CollectorState::collector, Function.identity()));
        collectors.orderedStream()
                .filter(collector -> properties.isEnabled(collector.name()) && collector.available())
                .forEach(collector -> {
                    CollectorState state = states.get(collector.name());
                    metrics.register(collector.name(), state == null ? null : state.lastSuccessAt());
                });
    }
}
