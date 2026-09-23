package ch.furchert.homelab.data.netmon.collector;

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
 * (collector -> runner -> metrics -> collectors).
 */
@Component
class CollectorMetricsInitializer {

    private final ObjectProvider<NetmonCollector> collectors;
    private final CollectorStateRepository repository;
    private final CollectorMetrics metrics;

    CollectorMetricsInitializer(ObjectProvider<NetmonCollector> collectors,
                                CollectorStateRepository repository,
                                CollectorMetrics metrics) {
        this.collectors = collectors;
        this.repository = repository;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerGauges() {
        Map<String, CollectorState> states = repository.findAll().stream()
                .collect(Collectors.toMap(CollectorState::collector, Function.identity()));
        collectors.orderedStream().forEach(collector -> {
            CollectorState state = states.get(collector.name());
            metrics.register(collector.name(), state == null ? null : state.lastSuccessAt());
        });
    }
}
