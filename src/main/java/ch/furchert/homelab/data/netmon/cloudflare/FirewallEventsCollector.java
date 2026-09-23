package ch.furchert.homelab.data.netmon.cloudflare;

import ch.furchert.homelab.data.config.CloudflareProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.enrichment.Sighting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@code cloudflare-firewall} (docs/060 §4.1, §4.2 query B): raw firewall events with keyset paging.
 * <ul>
 *   <li>{@code since} = {@code last_window_end - 10 min} (overlap; the natural key absorbs duplicates),
 *       or {@code until - 24 h} the first time and after a gap; {@code until} = now − 2 min.</li>
 *   <li>A full page continues at {@code datetime_geq} = its last {@code datetime}; if the whole page shares
 *       one timestamp it continues with {@code datetime_gt} and the run reports {@code truncated}.</li>
 *   <li>At most {@code firewall-max-pages} pages per run. On reaching {@code until}, the high-water mark
 *       becomes {@code until}; on hitting the cap it becomes the last fetched {@code datetime}.</li>
 * </ul>
 */
@Component
public class FirewallEventsCollector implements NetmonCollector {

    public static final String NAME = "cloudflare-firewall";
    static final Duration INGEST_DELAY = Duration.ofMinutes(2);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    static final Duration CATCH_UP = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(FirewallEventsCollector.class);

    private final CloudflareGraphqlClient client;
    private final FirewallEventRepository repository;
    private final IpEnrichmentService enrichment;
    private final CollectorStateRepository state;
    private final CloudflareProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;

    public FirewallEventsCollector(CloudflareGraphqlClient client, FirewallEventRepository repository,
                                   IpEnrichmentService enrichment, CollectorStateRepository state,
                                   CloudflareProperties properties, CollectorRunner runner, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.enrichment = enrichment;
        this.state = state;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(cron = "30 */5 * * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return Duration.ofMinutes(5);
    }

    @Override
    public void collect() {
        Instant until = clock.instant().truncatedTo(ChronoUnit.SECONDS).minus(INGEST_DELAY);
        Instant floor = until.minus(CATCH_UP);
        Instant previous = state.find(NAME).map(CollectorState::lastWindowEnd).orElse(null);
        Instant since;
        if (previous == null) {
            since = floor;
        } else {
            if (previous.isBefore(floor)) {
                log.info("[{}] gap skipped from={} to={}", NAME, previous, floor);
            }
            since = max(previous.minus(OVERLAP), floor);
        }

        int limit = properties.firewallPageSize();
        boolean exclusive = false;
        boolean truncated = false;
        boolean reachedEnd = false;
        Instant lastFetched = null;
        for (int pages = 0; pages < properties.firewallMaxPages(); pages++) {
            Page<FirewallEvent> page = client.firewallEvents(since, exclusive, until, limit);
            List<FirewallEvent> events = page.items();
            repository.insertAll(events);
            enrichment.record("firewall", FirewallEventRepository.SOURCE, sightings(events));
            if (!page.full()) {
                reachedEnd = true;
                break;
            }
            if (events.isEmpty()) {
                // A full page whose rows were all unusable gives no keyset position to continue from.
                truncated = true;
                break;
            }
            Instant first = events.getFirst().occurredAt();
            Instant last = events.getLast().occurredAt();
            lastFetched = last;
            exclusive = first.equals(last);
            truncated |= exclusive;
            since = last;
        }

        if (reachedEnd) {
            state.updateWindowEnd(NAME, until);
        } else if (lastFetched != null) {
            log.info("[{}] page cap of {} reached; resuming from the last fetched event next run", NAME,
                    properties.firewallMaxPages());
            state.updateWindowEnd(NAME, lastFetched);
        }
        if (truncated) {
            throw new CollectorWarning(ErrorCode.TRUNCATED,
                    "firewall-event paging was truncated at one timestamp");
        }
    }

    private static List<Sighting> sightings(List<FirewallEvent> events) {
        return events.stream()
                .map(e -> new Sighting(e.clientIp(), e.occurredAt(), e.occurredAt(), e.country(), e.asn(), e.asnOrg()))
                .toList();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
