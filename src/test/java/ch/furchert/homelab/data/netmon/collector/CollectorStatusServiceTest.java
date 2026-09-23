package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** stale = lastSuccessAt older than 3 x cadence (docs/060 §7.2); start time stands in before the first success. */
class CollectorStatusServiceTest {

    private static final Instant START = Instant.parse("2026-09-23T00:00:00Z");

    private final CollectorStateRepository repository = mock(CollectorStateRepository.class);

    private static NetmonCollector collector(String name, Duration cadence) {
        return new NetmonCollector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration cadence() {
                return cadence;
            }

            @Override
            public void collect() {
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
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

    private CollectorStatusService service(MutableClock clock, Map<String, NetmonProperties.Collector> toggles) {
        NetmonProperties properties = new NetmonProperties(new NetmonProperties.Api(List.of("furchert-ch")), toggles);
        return new CollectorStatusService(
                List.of(collector("retention", Duration.ofDays(1)), collector("lan", Duration.ofMinutes(15))),
                repository, properties, clock);
    }

    private static CollectorState state(String name, Instant lastSuccess, int failures, String code) {
        return new CollectorState(name, null, null, null, lastSuccess, failures, null, code);
    }

    @Test
    void collectorsAreSortedByName() {
        MutableClock clock = new MutableClock(START);
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service(clock, Map.of()).statuses()).extracting(CollectorStatus::name).containsExactly("lan", "retention");
    }

    @Test
    void neverSucceededIsNotStaleWithinThreeCadencesOfStartup() {
        MutableClock clock = new MutableClock(START);
        CollectorStatusService service = service(clock, Map.of());
        when(repository.findAll()).thenReturn(List.of());

        clock.now = START.plus(Duration.ofMinutes(45));
        assertThat(service.statuses()).filteredOn(s -> s.name().equals("lan")).singleElement()
                .extracting(CollectorStatus::stale).isEqualTo(false);

        clock.now = START.plus(Duration.ofMinutes(46));
        assertThat(service.statuses()).filteredOn(s -> s.name().equals("lan")).singleElement()
                .extracting(CollectorStatus::stale).isEqualTo(true);
    }

    @Test
    void staleIsMeasuredFromLastSuccess() {
        MutableClock clock = new MutableClock(START);
        CollectorStatusService service = service(clock, Map.of());
        Instant lastSuccess = START.plus(Duration.ofDays(1));
        when(repository.findAll()).thenReturn(List.of(state("retention", lastSuccess, 0, null)));

        clock.now = lastSuccess.plus(Duration.ofHours(72));
        assertThat(service.statuses()).filteredOn(s -> s.name().equals("retention")).singleElement()
                .extracting(CollectorStatus::stale).isEqualTo(false);

        clock.now = lastSuccess.plus(Duration.ofHours(72)).plusSeconds(1);
        assertThat(service.statuses()).filteredOn(s -> s.name().equals("retention")).singleElement()
                .satisfies(s -> {
                    assertThat(s.stale()).isTrue();
                    assertThat(s.lastSuccessAt()).isEqualTo(lastSuccess);
                });
    }

    @Test
    void disabledCollectorIsNeverStale() {
        MutableClock clock = new MutableClock(START);
        CollectorStatusService service = service(clock, Map.of("lan", new NetmonProperties.Collector(false)));
        when(repository.findAll()).thenReturn(List.of());

        clock.now = START.plus(Duration.ofDays(30));
        assertThat(service.statuses()).filteredOn(s -> s.name().equals("lan")).singleElement()
                .satisfies(s -> {
                    assertThat(s.enabled()).isFalse();
                    assertThat(s.stale()).isFalse();
                });
    }

    @Test
    void failureCountAndCodeArePassedThrough() {
        MutableClock clock = new MutableClock(START);
        when(repository.findAll()).thenReturn(List.of(state("lan", null, 3, "credentials")));

        assertThat(service(clock, Map.of()).statuses()).filteredOn(s -> s.name().equals("lan")).singleElement()
                .satisfies(s -> {
                    assertThat(s.consecutiveFailures()).isEqualTo(3);
                    assertThat(s.lastErrorCode()).isEqualTo("credentials");
                });
    }
}
