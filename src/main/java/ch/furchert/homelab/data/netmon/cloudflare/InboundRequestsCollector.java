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
import java.util.ArrayList;
import java.util.List;

/**
 * {@code cloudflare-requests} (docs/060 §4.1, §4.2 query A): hourly request groups at 5-minute freshness.
 * Each run walks the hours from the high-water mark ({@code last_window_end}, the end of the newest
 * contiguous final hour) to the current hour and replaces every window it queries. An hour is written
 * with {@code is_final=true} once {@code now >= hour end + 15 min}; only then does the high-water mark
 * move past it. Catch-up is capped at 24 h and throttled to {@code max-queries-per-run} queries.
 */
@Component
public class InboundRequestsCollector implements NetmonCollector {

    public static final String NAME = "cloudflare-requests";
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration FINAL_DELAY = Duration.ofMinutes(15);
    static final Duration INGEST_DELAY = Duration.ofMinutes(2);
    static final Duration CATCH_UP = Duration.ofHours(24);
    static final Duration SLICE = Duration.ofMinutes(5);
    static final int SLICES = 12;

    private static final Logger log = LoggerFactory.getLogger(InboundRequestsCollector.class);

    private final CloudflareGraphqlClient client;
    private final InboundRequestRepository repository;
    private final IpEnrichmentService enrichment;
    private final CollectorStateRepository state;
    private final CloudflareProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;

    public InboundRequestsCollector(CloudflareGraphqlClient client, InboundRequestRepository repository,
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

    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
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

    /** Without token/zone id every run fails with {@code credentials}; no NaN-forever gauge (see interface). */
    @Override
    public boolean exportsFreshnessGauge() {
        return properties.hasCredentials();
    }

    @Override
    public void collect() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        Instant floor = currentHour.minus(CATCH_UP);
        Instant start = state.find(NAME).map(CollectorState::lastWindowEnd).orElse(null);
        if (start == null) {
            start = floor;
        } else {
            start = start.truncatedTo(ChronoUnit.HOURS);
            if (start.isBefore(floor)) {
                log.info("[{}] gap skipped from={} to={}", NAME, start, floor);
                state.updateWindowEnd(NAME, floor);
                start = floor;
            }
        }

        int[] queriesLeft = {properties.maxQueriesPerRun()};
        boolean truncated = false;
        boolean contiguousFinal = true;
        for (Instant hour = start; !hour.isAfter(currentHour); hour = hour.plus(HOUR)) {
            Instant end = hour.plus(HOUR);
            Instant until = min(end, now.minus(INGEST_DELAY));
            if (!until.isAfter(hour)) {
                break; // the current hour has no ingested data yet
            }
            if (queriesLeft[0] < 1) {
                log.info("[{}] catch-up throttled at {} queries; continuing next run", NAME, properties.maxQueriesPerRun());
                break;
            }
            WindowResult window = collectWindow(hour, until, queriesLeft);
            if (window == null) {
                log.info("[{}] catch-up throttled at {} queries; continuing next run", NAME, properties.maxQueriesPerRun());
                break;
            }
            truncated |= window.truncated();
            boolean isFinal = !now.isBefore(end.plus(FINAL_DELAY));
            repository.replaceWindow(hour, end, isFinal, window.groups());
            enrichment.record("inbound", InboundRequestRepository.SOURCE, sightings(window.groups(), hour, until));
            if (isFinal && contiguousFinal) {
                state.updateWindowEnd(NAME, end);
            } else {
                contiguousFinal = false;
            }
        }
        if (truncated) {
            throw new CollectorWarning(ErrorCode.TRUNCATED,
                    "request groups of at least one 5-minute slice exceeded the page limit");
        }
    }

    /**
     * One query for the hour; if the page is full, 12 five-minute slices summed per natural key.
     *
     * @return {@code null} if the remaining query budget cannot cover the slicing
     */
    private WindowResult collectWindow(Instant hour, Instant until, int[] queriesLeft) {
        int limit = properties.groupsPageSize();
        Page<RequestGroup> page = client.requestGroups(hour, until, limit);
        queriesLeft[0]--;
        if (!page.full()) {
            return new WindowResult(InboundRequestRepository.aggregate(page.items()), false);
        }
        if (queriesLeft[0] < SLICES) {
            return null;
        }
        List<RequestGroup> all = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < SLICES; i++) {
            Instant sliceStart = hour.plus(SLICE.multipliedBy(i));
            if (!sliceStart.isBefore(until)) {
                break;
            }
            Page<RequestGroup> slice = client.requestGroups(sliceStart, min(sliceStart.plus(SLICE), until), limit);
            queriesLeft[0]--;
            truncated |= slice.full();
            all.addAll(slice.items());
        }
        return new WindowResult(InboundRequestRepository.aggregate(all), truncated);
    }

    private static List<Sighting> sightings(List<RequestGroup> groups, Instant from, Instant until) {
        return groups.stream()
                .map(g -> new Sighting(g.clientIp(), from, until, g.country(), null, null))
                .toList();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private record WindowResult(List<RequestGroup> groups, boolean truncated) {
    }
}
