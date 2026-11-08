package ch.furchert.homelab.data.webanalytics.ingest;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Seeds {@link IngestActivity} once at startup from {@code webanalytics.events} (docs/100 §8.2). */
@Component
class IngestActivitySeeder {

    private final IngestActivity activity;
    private final WebAnalyticsRepository repository;
    private final Clock clock;

    IngestActivitySeeder(IngestActivity activity, WebAnalyticsRepository repository, Clock clock) {
        this.activity = activity;
        this.repository = repository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        activity.seedStarted();
        WebAnalyticsRepository.Activity stored = repository.activity(dayStart);
        activity.seedCompleted(day, stored.capCountedToday(), stored.newestReceivedAt());
    }
}
