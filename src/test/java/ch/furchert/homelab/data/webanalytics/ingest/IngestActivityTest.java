package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** In-memory ingest activity (docs/100 §8.2): {@code lastEventAt} here, {@code eventsToday} from {@link IngestCaps}. */
class IngestActivityTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
    private final IngestCaps caps = new IngestCaps(clock, IngestCapsTest.properties(100, 100, 50));
    private final IngestActivity activity = new IngestActivity(caps);

    @Test
    void lastEventAtKeepsTheNewestReceipt() {
        activity.recordNewest(Instant.parse("2026-11-08T11:59:00Z"));
        activity.recordNewest(Instant.parse("2026-11-08T11:58:00Z"));
        activity.recordNewest(null);
        assertThat(activity.snapshot().lastEventAt()).isEqualTo(Instant.parse("2026-11-08T11:59:00Z"));
    }

    @Test
    void eventsTodayComesFromTheCapLedger() {
        caps.seedStarted();
        caps.seedCompleted(Map.of(Instant.parse("2026-11-08T10:00:00Z"), 7L, Instant.parse("2026-11-07T23:00:00Z"), 3L));
        assertThat(activity.snapshot().eventsToday()).isEqualTo(7);
    }
}
