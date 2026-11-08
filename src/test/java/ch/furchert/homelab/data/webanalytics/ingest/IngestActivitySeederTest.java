package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A failing seed query must not abort startup (PR #59 review); counting continues in memory. */
class IngestActivitySeederTest {

    @Test
    void failingSeedQueryIsToleratedAndCountingContinues() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        IngestActivity activity = new IngestActivity(clock);
        WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
        when(repository.activity(any())).thenThrow(new DataAccessResourceFailureException("db gone"));

        assertThatCode(() -> new IngestActivitySeeder(activity, repository, clock).seed()).doesNotThrowAnyException();

        activity.recordStored(List.of(Instant.parse("2026-11-08T12:00:01Z")), Instant.parse("2026-11-08T12:00:01Z"));
        assertThat(activity.snapshot().eventsToday()).isEqualTo(1);
    }
}
