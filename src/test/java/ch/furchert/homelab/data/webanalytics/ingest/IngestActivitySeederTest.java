package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Startup seed of the cap ledger and {@code lastEventAt} (docs/100 §6.4, §8.2): from the start of the UTC day of
 * now − 2 h; a failing seed query must not abort startup (PR #59 review), counting continues in memory and one re-seed
 * runs 5 minutes later (WA-5 plan review A3).
 */
class IngestActivitySeederTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T01:00:00Z"));
    private final IngestCaps caps = new IngestCaps(clock, IngestCapsTest.properties(100, 100, 50));
    private final IngestActivity activity = new IngestActivity(caps);
    private final WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @SuppressWarnings("unchecked")
    private IngestActivitySeeder seeder() {
        ObjectProvider<TaskScheduler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        return new IngestActivitySeeder(activity, caps, repository, clock, provider);
    }

    @Test
    void seedReadsFromTheStartOfTheUtcDayOfNowMinusTwoHours() {
        // 01:00Z − 2 h is on 2026-11-08, so yesterday's hours are read as well (ended windows still count there).
        when(repository.analyticsPerHour(Instant.parse("2026-11-08T00:00:00Z")))
                .thenReturn(Map.of(Instant.parse("2026-11-09T00:00:00Z"), 4L, Instant.parse("2026-11-08T23:00:00Z"), 9L));
        when(repository.newestReceivedAt()).thenReturn(Instant.parse("2026-11-09T00:59:00Z"));
        seeder().onReady();
        assertThat(activity.snapshot().eventsToday()).isEqualTo(4);
        assertThat(activity.snapshot().lastEventAt()).isEqualTo(Instant.parse("2026-11-09T00:59:00Z"));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void failingSeedQueryIsToleratedCountsFromStartupAndRetriesOnceAfterFiveMinutes() {
        when(repository.analyticsPerHour(any())).thenThrow(new DataAccessResourceFailureException("db gone"));
        assertThatCode(() -> seeder().onReady()).doesNotThrowAnyException();
        verify(scheduler).schedule(any(Runnable.class), eq(Instant.parse("2026-11-09T01:05:00Z")));
        assertThat(activity.snapshot().eventsToday()).isZero();
    }
}
