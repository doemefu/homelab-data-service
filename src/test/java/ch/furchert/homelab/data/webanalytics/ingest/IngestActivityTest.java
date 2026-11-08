package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** In-memory ingest activity (docs/100 §8.2; rev 3.4 F14). */
class IngestActivityTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T23:58:00Z"));
    private final IngestActivity activity = new IngestActivity(clock);

    @Test
    void countsOnlyTodaysCapCountedEventsAndResetsAtUtcMidnight() {
        activity.recordStored(List.of(Instant.parse("2026-11-08T23:57:00Z"), Instant.parse("2026-11-08T23:57:30Z")),
                Instant.parse("2026-11-08T23:57:45Z"));
        assertThat(activity.snapshot().eventsToday()).isEqualTo(2);
        assertThat(activity.snapshot().lastEventAt()).isEqualTo(Instant.parse("2026-11-08T23:57:45Z"));

        clock.advance(Duration.ofMinutes(3)); // 2026-11-09T00:01Z
        assertThat(activity.snapshot().eventsToday()).isZero();
        activity.recordStored(List.of(Instant.parse("2026-11-08T23:59:59Z"), Instant.parse("2026-11-09T00:00:30Z")),
                Instant.parse("2026-11-09T00:00:30Z"));
        assertThat(activity.snapshot().eventsToday()).isEqualTo(1); // yesterday's receipt does not count
    }

    @Test
    void seedAddsTheDeltaRecordedWhileTheSeedQueryRan() {
        clock.set(Instant.parse("2026-11-08T12:00:00Z"));
        activity.seedStarted();
        activity.recordStored(List.of(Instant.parse("2026-11-08T12:00:00Z")), Instant.parse("2026-11-08T12:00:00Z"));
        activity.seedCompleted(LocalDate.parse("2026-11-08"), 40, Instant.parse("2026-11-08T11:00:00Z"));
        assertThat(activity.snapshot().eventsToday()).isEqualTo(41);
        assertThat(activity.snapshot().lastEventAt()).isEqualTo(Instant.parse("2026-11-08T12:00:00Z"));
    }

    @Test
    void seedOfAnotherDayDoesNotOverwriteTodaysCount() {
        clock.set(Instant.parse("2026-11-09T00:00:05Z"));
        activity.seedStarted();
        activity.seedCompleted(LocalDate.parse("2026-11-08"), 40, null);
        assertThat(activity.snapshot().eventsToday()).isZero();
    }
}
