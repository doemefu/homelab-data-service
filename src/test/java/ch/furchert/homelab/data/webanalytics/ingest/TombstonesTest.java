package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Erasure tombstones (docs/100 §6.5; #56 comment 1): 2 h TTL, hard cap, oldest evicted, refreshed on repeat. */
class TombstonesTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
    private final Tombstones tombstones = new Tombstones(clock);

    @Test
    void anIdIsTombstonedForTwoHours() {
        UUID id = UUID.randomUUID();
        tombstones.add(id);
        clock.advance(Duration.ofMinutes(119));
        assertThat(tombstones.contains(id)).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(tombstones.contains(id)).isFalse();
    }

    @Test
    void aRepeatedAddRefreshesTheEntry() {
        UUID id = UUID.randomUUID();
        tombstones.add(id);
        clock.advance(Duration.ofMinutes(90));
        tombstones.add(id);
        clock.advance(Duration.ofMinutes(90));
        assertThat(tombstones.contains(id)).isTrue();
    }

    @Test
    void theSetIsBoundedAndEvictsTheOldest() {
        UUID oldest = UUID.randomUUID();
        tombstones.add(oldest);
        UUID refreshed = UUID.randomUUID();
        tombstones.add(refreshed);
        for (int i = 0; i < Tombstones.MAX_ENTRIES - 2; i++) {
            tombstones.add(UUID.randomUUID());
        }
        tombstones.add(refreshed); // moves to the newest position
        assertThat(tombstones.size()).isEqualTo(Tombstones.MAX_ENTRIES);
        tombstones.add(UUID.randomUUID());
        assertThat(tombstones.size()).isEqualTo(Tombstones.MAX_ENTRIES);
        assertThat(tombstones.contains(oldest)).isFalse();
        assertThat(tombstones.contains(refreshed)).isTrue();
    }
}
