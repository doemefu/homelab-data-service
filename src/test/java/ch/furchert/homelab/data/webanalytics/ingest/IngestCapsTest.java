package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.support.MutableClock;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The cap ledger (docs/100 §6.4, rev 3.5 + WA-5 plan rulings R4, R5, R8, R10) with a pinned clock. */
class IngestCapsTest {

    private static final Instant NOON = Instant.parse("2026-11-09T12:30:00Z");

    private final MutableClock clock = new MutableClock(NOON);
    private final EventValidator validator = new EventValidator();

    private IngestCaps caps(int daily, int hourly, int visitor) {
        IngestCaps caps = new IngestCaps(clock, properties(daily, hourly, visitor));
        caps.seedStarted();
        caps.seedCompleted(Map.of());
        return caps;
    }

    static WebAnalyticsProperties properties(int daily, int hourly, int visitor) {
        return new WebAnalyticsProperties(new WebAnalyticsProperties.Api(List.of("furchert-ch"), List.of("furchert-ch")),
                ZoneId.of("Europe/Zurich"), new WebAnalyticsProperties.Coverage(null),
                new WebAnalyticsProperties.Ingest(daily, hourly, visitor), new WebAnalyticsProperties.Retention(396));
    }

    private ValidEvent event(UUID visitor, Instant receivedAt) {
        Map<String, Object> raw = new java.util.LinkedHashMap<>(WebAnalyticsFixtures.event("pageview", clock.instant()));
        raw.put("visitorId", visitor.toString());
        raw.put("receivedAt", receivedAt.toString());
        raw.put("occurredAt", receivedAt.minusSeconds(1).toString());
        EventValidator.Result result = validator.validate(WebAnalyticsFixtures.toDto(raw), clock.instant());
        assertThat(result.isValid()).isTrue();
        return result.event();
    }

    private List<ValidEvent> events(int n, Instant receivedAt) {
        List<ValidEvent> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(event(UUID.randomUUID(), receivedAt));
        }
        return list;
    }

    private void accept(IngestCaps caps, List<ValidEvent> events) {
        IngestCaps.Decision decision = caps.reserve(events, Map.of());
        assertThat(decision.capped()).isNull();
        caps.commit(decision.reservation(), events);
    }

    @Test
    void hourlyCapIsAllOrNothingAtTheBoundary() {
        IngestCaps caps = caps(100, 5, 50);
        accept(caps, events(4, NOON));

        IngestCaps.Decision two = caps.reserve(events(2, NOON), Map.of());
        assertThat(two.capped()).isNotNull();
        assertThat(two.capped().scope()).isEqualTo(IngestCaps.Scope.HOUR);
        assertThat(two.capped().events()).isEqualTo(2);
        assertThat(IngestCaps.format(two.capped().retryAt())).isEqualTo("2026-11-09T13:00:00Z");
        assertThat(caps.eventsToday()).isEqualTo(4); // nothing charged

        accept(caps, events(1, NOON)); // exactly at the cap
        assertThat(caps.reserve(events(1, NOON), Map.of()).capped()).isNotNull();
        assertThat(caps.eventsToday()).isEqualTo(5);
    }

    @Test
    void dailyCapTakesPrecedenceAndRetriesAtMidnightUtc() {
        IngestCaps caps = caps(3, 3, 50);
        accept(caps, events(3, NOON));
        IngestCaps.Decision decision = caps.reserve(events(1, NOON), Map.of());
        assertThat(decision.capped().scope()).isEqualTo(IngestCaps.Scope.DAY);
        assertThat(IngestCaps.format(decision.capped().retryAt())).isEqualTo("2026-11-10T00:00:00Z");
    }

    @Test
    void anEmptyBatchIsNeverCappedEvenAtZeroCaps() {
        IngestCaps caps = caps(0, 0, 5);
        IngestCaps.Decision decision = caps.reserve(List.of(), Map.of());
        assertThat(decision.capped()).isNull();
        caps.commit(decision.reservation(), List.of());
        assertThat(caps.reserve(events(1, NOON), Map.of()).capped().scope()).isEqualTo(IngestCaps.Scope.DAY);
    }

    @Test
    void eachEventIsChargedToTheWindowOfItsOwnReceivedAtAndOnlyCurrentWindowsAreEnforced() {
        clock.set(Instant.parse("2026-11-09T13:00:30Z"));
        IngestCaps caps = caps(100, 2, 50);
        Instant previousHour = Instant.parse("2026-11-09T12:59:50Z");
        // Three events of the ended 12:00 hour pass although the hourly cap is 2: that window is over.
        accept(caps, events(3, previousHour));
        // The current 13:00 hour is still empty.
        accept(caps, events(2, clock.instant()));
        assertThat(caps.reserve(events(1, clock.instant()), Map.of()).capped().scope()).isEqualTo(IngestCaps.Scope.HOUR);
        // The day window holds all five.
        assertThat(caps.eventsToday()).isEqualTo(5);
    }

    @Test
    void concurrentReservationsCountTowardsTheCapAndAReleaseFreesThem() {
        IngestCaps caps = caps(100, 3, 50);
        IngestCaps.Decision first = caps.reserve(events(2, NOON), Map.of());
        assertThat(caps.reserve(events(2, NOON), Map.of()).capped()).isNotNull();
        caps.release(first.reservation());
        assertThat(caps.reserve(events(2, NOON), Map.of()).capped()).isNull();
    }

    @Test
    void aLostInsertIsNotCharged() {
        IngestCaps caps = caps(100, 3, 50);
        List<ValidEvent> batch = events(3, NOON);
        IngestCaps.Decision decision = caps.reserve(batch, Map.of());
        caps.commit(decision.reservation(), batch.subList(0, 2)); // one row lost a race
        assertThat(caps.eventsToday()).isEqualTo(2);
        assertThat(caps.reserve(events(1, NOON), Map.of()).capped()).isNull();
    }

    @Test
    void perVisitorCapRejectsTheExcessIndividuallyFromTheSeed() {
        IngestCaps caps = caps(100, 100, 3);
        UUID visitor = UUID.randomUUID();
        List<ValidEvent> batch = List.of(event(visitor, NOON), event(visitor, NOON), event(visitor, NOON));
        IngestCaps.Decision decision = caps.reserve(batch, Map.of(visitor, 2L)); // 2 stored today already
        assertThat(decision.capped()).isNull();
        assertThat(decision.visitorCapped()).containsExactly(batch.get(1).eventId(), batch.get(2).eventId());
        caps.commit(decision.reservation(), batch.subList(0, 1));

        // The key is cached at the cap: the next event is rejected without a new seed.
        assertThat(caps.unseenVisitors(List.of(visitor))).isEmpty();
        ValidEvent next = event(visitor, NOON);
        assertThat(caps.reserve(List.of(next), Map.of()).visitorCapped()).containsExactly(next.eventId());
        assertThat(caps.eventsToday()).isEqualTo(1);
    }

    @Test
    void aKeyIsKeptWhenAllItsEventsAreRejected() {
        IngestCaps caps = caps(100, 100, 2);
        UUID visitor = UUID.randomUUID();
        ValidEvent only = event(visitor, NOON);
        IngestCaps.Decision decision = caps.reserve(List.of(only), Map.of(visitor, 2L));
        assertThat(decision.visitorCapped()).containsExactly(only.eventId());
        caps.commit(decision.reservation(), List.of());
        assertThat(caps.unseenVisitors(List.of(visitor))).isEmpty();
    }

    @Test
    void perVisitorCountsStartOverAtMidnightUtcAndPreviousDayEventsAreNotChecked() {
        clock.set(Instant.parse("2026-11-09T23:59:00Z"));
        IngestCaps caps = caps(100, 100, 1);
        UUID visitor = UUID.randomUUID();
        accept(caps, List.of(event(visitor, clock.instant())));

        clock.set(Instant.parse("2026-11-10T00:00:30Z"));
        assertThat(caps.unseenVisitors(List.of(visitor))).containsExactly(visitor);
        // An event received before midnight belongs to the ended day: not checked.
        ValidEvent late = event(visitor, Instant.parse("2026-11-09T23:59:50Z"));
        assertThat(caps.reserve(List.of(late), Map.of()).visitorCapped()).isEmpty();
    }

    @Test
    void forgetVisitorDropsTheKey() {
        IngestCaps caps = caps(100, 100, 5);
        UUID visitor = UUID.randomUUID();
        accept(caps, List.of(event(visitor, NOON)));
        caps.forgetVisitor(visitor);
        assertThat(caps.unseenVisitors(List.of(visitor))).containsExactly(visitor);
    }

    @Test
    void perVisitorMapIsBoundedAtTwiceTheDailyCap() {
        IngestCaps caps = caps(2, 100, 5);
        UUID first = UUID.randomUUID();
        caps.reserve(List.of(), Map.of(first, 0L));
        for (int i = 0; i < 4; i++) {
            caps.reserve(List.of(), Map.of(UUID.randomUUID(), 0L));
        }
        assertThat(caps.visitorKeys()).isEqualTo(4);
        assertThat(caps.unseenVisitors(List.of(first))).containsExactly(first); // oldest evicted
    }

    @Test
    void cappedGaugeIsOneUntilRetryAtAndReturnsToZeroWithoutTraffic() {
        IngestCaps caps = caps(100, 1, 50);
        accept(caps, events(1, NOON));
        assertThat(caps.capped()).isFalse();
        caps.reserve(events(1, NOON), Map.of());
        assertThat(caps.capped()).isTrue();
        clock.set(Instant.parse("2026-11-09T12:59:59Z"));
        assertThat(caps.capped()).isTrue();
        clock.set(Instant.parse("2026-11-09T13:00:00Z"));
        assertThat(caps.capped()).isFalse();
    }

    @Test
    void seedRestoresCountsAndTheCappedState() {
        IngestCaps caps = new IngestCaps(clock, properties(100, 3, 50));
        caps.seedStarted();
        accept(caps, events(1, NOON)); // a batch committed while the seed query ran
        caps.seedCompleted(Map.of(Instant.parse("2026-11-09T12:00:00Z"), 2L,
                Instant.parse("2026-11-09T08:00:00Z"), 5L));
        assertThat(caps.eventsToday()).isEqualTo(8);
        assertThat(caps.capped()).isTrue(); // the 12:00 hour holds 3 = cap
        clock.advance(Duration.ofMinutes(30));
        assertThat(caps.capped()).isFalse();

        IngestCaps daily = new IngestCaps(clock, properties(5, 100, 50));
        daily.seedStarted();
        daily.seedCompleted(Map.of(Instant.parse("2026-11-09T08:00:00Z"), 5L));
        assertThat(daily.capped()).isTrue();
        clock.set(Instant.parse("2026-11-10T00:00:00Z"));
        assertThat(daily.capped()).isFalse();
    }

    @Test
    void aFailedSeedCountsFromStartup() {
        IngestCaps caps = new IngestCaps(clock, properties(100, 100, 50));
        caps.seedStarted();
        accept(caps, events(2, NOON));
        caps.seedFailed();
        assertThat(caps.eventsToday()).isEqualTo(2);
    }

    @Test
    void eventsTodayResetsAtMidnightUtc() {
        IngestCaps caps = caps(100, 100, 50);
        accept(caps, events(2, NOON));
        clock.set(Instant.parse("2026-11-10T00:00:01Z"));
        assertThat(caps.eventsToday()).isZero();
    }
}
