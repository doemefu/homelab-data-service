package ch.furchert.homelab.data.netmon.login;

import ch.furchert.homelab.data.config.AuthServiceProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
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
 * {@code login-events} (docs/060 §4.1, §7.6): pulls the auth-service login-event outbox every minute.
 * <ul>
 *   <li>Pages with {@code after = collector_state.cursor} (0 on the first run) while {@code hasMore} is true, at most
 *       {@code netmon.auth-service.max-pages} pages per run; a run that stops at the cap resumes a minute later.</li>
 *   <li>Each page is upserted on {@code event_id} and its public IPs are enriched (§4.3, data set {@code login})
 *       before the cursor moves to the page's {@code nextAfter}, so a crash replays at most one page harmlessly.
 *       Rows violating the §3.3 contract are skipped (the cursor still covers them) and the run ends with the
 *       warning {@code partial}, carrying only the count.</li>
 *   <li>A run that drains the outbox sets {@code last_window_end} to its start minus auth-service's 10 s settle
 *       window: every event recorded before that has been pulled.</li>
 *   <li>The outbox keeps 72 h (§4.1 catch-up cap); events purged during a longer outage are lost without a marker.
 *       A run that starts more than 72 h after the last success logs that once.</li>
 * </ul>
 * Status semantics live in {@link AuthServiceClient}: no secret → {@code credentials} failure and no freshness gauge;
 * a disabled outbox (503) → a successful run with an {@code upstream} warning ("no data yet", §7.2). Unlike the
 * other collectors, {@code credentials} failures also back off, so a wrong secret does not produce a failed client
 * authentication in auth-service every minute (docs/060 §7.6 amendment, homelab#134).
 */
@Component
public class LoginEventsCollector implements NetmonCollector {

    public static final String NAME = "login-events";
    /** auth-service serves only rows recorded at least this long ago ({@code app.login-events.settle}). */
    static final Duration PRODUCER_SETTLE = Duration.ofSeconds(10);
    static final String DATASET = "login";
    /** auth-service purges outbox rows older than this ({@code app.login-events.ttl}). */
    static final Duration OUTBOX_TTL = Duration.ofHours(72);

    private static final Logger log = LoggerFactory.getLogger(LoginEventsCollector.class);

    private final AuthServiceClient client;
    private final LoginEventRepository repository;
    private final IpEnrichmentService enrichment;
    private final CollectorStateRepository state;
    private final AuthServiceProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;

    public LoginEventsCollector(AuthServiceClient client, LoginEventRepository repository,
                                IpEnrichmentService enrichment, CollectorStateRepository state,
                                AuthServiceProperties properties, CollectorRunner runner, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.enrichment = enrichment;
        this.state = state;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(cron = "15 * * * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return Duration.ofMinutes(1);
    }

    /** Without the client secret every run fails with {@code credentials}; no NaN-forever gauge (see interface). */
    @Override
    public boolean exportsFreshnessGauge() {
        return properties.hasCredentials();
    }

    @Override
    public boolean backsOffAfter(ErrorCode code) {
        return code == ErrorCode.CREDENTIALS || NetmonCollector.super.backsOffAfter(code);
    }

    @Override
    public void collect() {
        Instant runStart = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        CollectorState current = state.find(NAME).orElse(null);
        if (current != null && current.lastSuccessAt() != null
                && current.lastSuccessAt().isBefore(runStart.minus(OUTBOX_TTL))) {
            log.warn("[{}] last success older than the 72 h outbox TTL; events purged meanwhile are lost", NAME);
        }
        long after = cursor(current);
        int skipped = 0;
        boolean drained = false;
        for (int pages = 0; pages < properties.maxPages() && !drained; pages++) {
            LoginEventPage page = client.page(after, properties.pageSize());
            repository.insertAll(page.events());
            enrichment.record(DATASET, LoginEventRepository.SOURCE, sightings(page.events()));
            skipped += page.skipped();
            if (page.nextAfter() > after) {
                after = page.nextAfter();
                state.updateCursor(NAME, Long.toString(after));
            } else if (page.hasMore()) {
                throw new CollectorException(ErrorCode.UPSTREAM, "auth-service reported more events without advancing");
            }
            if (!page.hasMore()) {
                state.updateWindowEnd(NAME, runStart.minus(PRODUCER_SETTLE));
                drained = true;
            }
        }
        if (!drained) {
            log.info("[{}] page cap of {} reached; continuing next run", NAME, properties.maxPages());
        }
        if (skipped > 0) {
            // Only the count, never the rows (docs/060 §10).
            throw new CollectorWarning(ErrorCode.PARTIAL,
                    "skipped " + skipped + " outbox rows that violate the login_events contract");
        }
    }

    private long cursor(CollectorState current) {
        String cursor = current == null ? null : current.cursor();
        if (cursor == null) {
            return 0;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        log.warn("[{}] stored cursor is not an outbox id; restarting from 0 (replays are idempotent)", NAME);
        return 0;
    }

    private static List<Sighting> sightings(List<LoginEvent> events) {
        return events.stream()
                .filter(e -> e.clientIp() != null)
                .map(e -> new Sighting(e.clientIp(), e.occurredAt(), e.occurredAt(), null, null, null))
                .toList();
    }
}
