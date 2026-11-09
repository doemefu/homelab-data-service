package ch.furchert.homelab.data.webanalytics.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Seeds {@link IngestCaps} (stored analytics events per UTC hour of {@code received_at}, from the start of the UTC day
 * of {@code now − 2 h}) and {@link IngestActivity} ({@code lastEventAt}) once at startup from
 * {@code webanalytics.events} (docs/100 §6.4, §8.2). A failing seed query never aborts startup: it is logged by class
 * name, the counts start from 0, and one re-seed is scheduled 5 minutes later (when a scheduler exists).
 */
@Component
class IngestActivitySeeder {

    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(IngestActivitySeeder.class);

    private final IngestActivity activity;
    private final IngestCaps caps;
    private final WebAnalyticsRepository repository;
    private final Clock clock;
    private final ObjectProvider<TaskScheduler> scheduler;

    IngestActivitySeeder(IngestActivity activity, IngestCaps caps, WebAnalyticsRepository repository, Clock clock,
                         ObjectProvider<TaskScheduler> scheduler) {
        this.activity = activity;
        this.caps = caps;
        this.repository = repository;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!seed()) {
            TaskScheduler taskScheduler = scheduler.getIfAvailable();
            if (taskScheduler != null) {
                taskScheduler.schedule(this::seed, clock.instant().plus(RETRY_AFTER));
            }
        }
    }

    /** @return false when the seed query failed */
    boolean seed() {
        Instant now = clock.instant();
        LocalDate from = LocalDate.ofInstant(now.minus(Duration.ofHours(2)), ZoneOffset.UTC);
        caps.seedStarted();
        try {
            caps.seedCompleted(repository.analyticsPerHour(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
            activity.recordNewest(repository.newestReceivedAt());
            return true;
        } catch (DataAccessException e) {
            log.warn("[webanalytics] ingest activity seed failed: {}; counting from startup",
                    e.getClass().getSimpleName());
            caps.seedFailed();
            return false;
        }
    }
}
