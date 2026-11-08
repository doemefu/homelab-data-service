package ch.furchert.homelab.data.webanalytics.ingest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-memory 2 h enquiry dedupe (docs/100 §6.2). */
class EnquiryDeduplicatorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T15:00:00Z"));
    private final EnquiryDeduplicator dedupe = new EnquiryDeduplicator(clock);

    @Test
    void secondReservationWithinTwoHoursIsRefused() {
        UUID id = UUID.randomUUID();
        assertThat(dedupe.reserve(id)).isTrue();
        clock.advance(Duration.ofMinutes(119));
        assertThat(dedupe.reserve(id)).isFalse();
    }

    @Test
    void reservationExpiresAfterTwoHours() {
        UUID id = UUID.randomUUID();
        assertThat(dedupe.reserve(id)).isTrue();
        clock.advance(Duration.ofHours(2));
        assertThat(dedupe.reserve(id)).isTrue();
    }

    @Test
    void releasedReservationCanBeTakenAgain() {
        UUID id = UUID.randomUUID();
        assertThat(dedupe.reserve(id)).isTrue();
        dedupe.release(id);
        assertThat(dedupe.reserve(id)).isTrue();
    }

    @Test
    void capEvictsTheOldestEntry() {
        UUID first = UUID.randomUUID();
        dedupe.reserve(first);
        for (int i = 1; i < EnquiryDeduplicator.MAX_ENTRIES; i++) {
            dedupe.reserve(UUID.randomUUID());
        }
        assertThat(dedupe.size()).isEqualTo(EnquiryDeduplicator.MAX_ENTRIES);
        dedupe.reserve(UUID.randomUUID());
        assertThat(dedupe.size()).isEqualTo(EnquiryDeduplicator.MAX_ENTRIES);
        assertThat(dedupe.reserve(first)).isTrue();
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
