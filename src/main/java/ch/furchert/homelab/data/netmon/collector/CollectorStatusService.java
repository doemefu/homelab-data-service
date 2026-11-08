package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collector freshness for the status APIs. {@code stale} means the last success is older than
 * {@link NetmonCollector#staleAfter()} (3 x the cadence by default, docs/060 §7.2). Before a collector's first
 * success the service start time is the reference, so a freshly deployed collector is not reported stale before it
 * could have run, but one that never succeeds becomes stale after that age. Disabled collectors are never stale.
 */
@Service
public class CollectorStatusService {

    private final List<NetmonCollector> collectors;
    private final CollectorStateRepository repository;
    private final NetmonProperties properties;
    private final Clock clock;
    private final Instant startedAt;

    public CollectorStatusService(List<NetmonCollector> collectors, CollectorStateRepository repository,
                                  NetmonProperties properties, Clock clock) {
        this.collectors = List.copyOf(collectors);
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** Statuses of the {@code netmon} group (docs/060 §7.2). */
    public List<CollectorStatus> statuses() {
        return statuses(NetmonCollector.NETMON_GROUP);
    }

    /** Statuses of the collectors in one {@link NetmonCollector#group()}. */
    public List<CollectorStatus> statuses(String group) {
        Instant now = clock.instant();
        Map<String, CollectorState> states = repository.findAll().stream()
                .collect(Collectors.toMap(CollectorState::collector, Function.identity()));
        return collectors.stream()
                .filter(collector -> group.equals(collector.group()))
                .sorted(Comparator.comparing(NetmonCollector::name))
                .map(collector -> toStatus(collector, states.get(collector.name()), now))
                .toList();
    }

    private CollectorStatus toStatus(NetmonCollector collector, CollectorState state, Instant now) {
        boolean enabled = properties.isEnabled(collector.name()) && collector.available();
        Instant lastSuccess = state == null ? null : state.lastSuccessAt();
        Instant reference = lastSuccess != null ? lastSuccess : startedAt;
        boolean stale = enabled
                && Duration.between(reference, now).compareTo(collector.staleAfter()) > 0;
        return new CollectorStatus(
                collector.name(),
                enabled,
                lastSuccess,
                state == null ? null : state.lastWindowEnd(),
                state == null ? 0 : state.consecutiveFailures(),
                state == null ? null : state.lastErrorCode(),
                stale);
    }
}
