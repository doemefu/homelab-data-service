package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollectorRunnerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private final CollectorStateRepository repository = mock(CollectorStateRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CollectorMetrics metrics = new CollectorMetrics(registry);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CollectorRunner runner(Map<String, NetmonProperties.Collector> toggles) {
        NetmonProperties properties = new NetmonProperties(new NetmonProperties.Api(List.of("furchert-ch")), toggles);
        return new CollectorRunner(repository, properties, metrics, clock);
    }

    private static NetmonCollector collector(String name, ThrowingRunnable body) {
        return new NetmonCollector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration cadence() {
                return Duration.ofMinutes(5);
            }

            @Override
            public void collect() throws Exception {
                body.run();
            }
        };
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void successIsRecordedAndExportedAsGauge() {
        boolean ran = runner(Map.of()).run(collector("demo", () -> {
        }));

        assertThat(ran).isTrue();
        verify(repository).recordAttempt("demo", NOW);
        verify(repository).recordSuccess("demo", NOW);
        assertThat(registry.get("netmon.collector.last.success.timestamp").tag("collector", "demo").gauge().value())
                .isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void collectorExceptionStoresItsCodeAndMessage() {
        boolean ran = runner(Map.of()).run(collector("demo", () -> {
            throw new CollectorException(ErrorCode.RATE_LIMITED, "HTTP 429 from upstream");
        }));

        assertThat(ran).isFalse();
        verify(repository).recordFailure("demo", ErrorCode.RATE_LIMITED, "CollectorException: HTTP 429 from upstream");
        verify(repository, never()).recordSuccess(anyString(), any());
    }

    @Test
    void foreignExceptionStoresClassNameOnly() {
        runner(Map.of()).run(collector("demo", () -> {
            throw new IllegalStateException("GET https://api.example/x?token=secret failed");
        }));

        verify(repository).recordFailure("demo", ErrorCode.INTERNAL, "IllegalStateException");
    }

    @Test
    void longCollectorMessagesAreTruncated() {
        runner(Map.of()).run(collector("demo", () -> {
            throw new CollectorException(ErrorCode.UPSTREAM, "x".repeat(1000));
        }));

        verify(repository).recordFailure("demo", ErrorCode.UPSTREAM, "CollectorException: " + "x".repeat(200));
    }

    @Test
    void disabledCollectorIsSkippedWithoutTouchingState() {
        AtomicBoolean called = new AtomicBoolean();
        boolean ran = runner(Map.of("demo", new NetmonProperties.Collector(false)))
                .run(collector("demo", () -> called.set(true)));

        assertThat(ran).isFalse();
        assertThat(called).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void overlappingRunIsSkipped() throws Exception {
        CollectorRunner runner = runner(Map.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NetmonCollector slow = collector("demo", () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
        });

        Thread first = Thread.ofVirtual().start(() -> runner.run(slow));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        boolean second = runner.run(slow);
        release.countDown();
        first.join(5000);

        assertThat(second).isFalse();
        verify(repository).recordAttempt("demo", NOW); // exactly once: the second run never started
    }

    @Test
    void warningCountsAsSuccessButKeepsTheCode() {
        boolean ran = runner(Map.of()).run(collector("demo", () -> {
            throw new CollectorWarning(ErrorCode.TRUNCATED, "slice exceeded the page limit");
        }));

        assertThat(ran).isTrue();
        verify(repository).recordSuccess("demo", NOW, ErrorCode.TRUNCATED, "CollectorWarning: slice exceeded the page limit");
        verify(repository, never()).recordFailure(anyString(), any(), anyString());
        assertThat(registry.get("netmon.collector.last.success.timestamp").tag("collector", "demo").gauge().value())
                .isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void unavailableCollectorIsSkippedLikeADisabledOne() {
        NetmonCollector unavailable = new NetmonCollector() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public Duration cadence() {
                return Duration.ofMinutes(5);
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public void collect() {
                throw new AssertionError("must not run");
            }
        };

        assertThat(runner(Map.of()).run(unavailable)).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void backoffGrowsExponentiallyUpTo30Minutes() {
        Duration five = Duration.ofMinutes(5);
        assertThat(CollectorRunner.backoff(five, 1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(CollectorRunner.backoff(five, 2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(CollectorRunner.backoff(five, 3)).isEqualTo(Duration.ofMinutes(20));
        assertThat(CollectorRunner.backoff(five, 4)).isEqualTo(Duration.ofMinutes(30));
        assertThat(CollectorRunner.backoff(five, 400)).isEqualTo(Duration.ofMinutes(30));
        assertThat(CollectorRunner.backoff(Duration.ofDays(1), 1)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void upstreamFailuresBackOffBetweenRuns() {
        // Two consecutive upstream failures, the last attempt 5 min ago: backoff is 10 min, so skip.
        when(repository.find("demo")).thenReturn(Optional.of(new CollectorState("demo", null, null,
                NOW.minus(Duration.ofMinutes(5)), null, 2, "CollectorException: HTTP 503", "upstream")));
        AtomicBoolean called = new AtomicBoolean();

        assertThat(runner(Map.of()).run(collector("demo", () -> called.set(true)))).isFalse();
        assertThat(called).isFalse();
        verify(repository, never()).recordAttempt(anyString(), any());
    }

    @Test
    void firstFailureKeepsTheNormalCadence() {
        when(repository.find("demo")).thenReturn(Optional.of(new CollectorState("demo", null, null,
                NOW.minus(Duration.ofMinutes(5)).plusSeconds(2), null, 1, "CollectorException: HTTP 429", "rate_limited")));

        assertThat(runner(Map.of()).run(collector("demo", () -> {
        }))).isTrue();
    }

    @Test
    void credentialFailuresDoNotBackOff() {
        when(repository.find("demo")).thenReturn(Optional.of(new CollectorState("demo", null, null,
                NOW.minus(Duration.ofMinutes(5)), null, 9, "CollectorException: HTTP 401", "credentials")));

        assertThat(runner(Map.of()).run(collector("demo", () -> {
        }))).isTrue();
    }

    @Test
    void aCollectorCanAddCodesToTheBackoff() {
        when(repository.find("demo")).thenReturn(Optional.of(new CollectorState("demo", null, null,
                NOW.minus(Duration.ofMinutes(5)), null, 2, "CollectorException: HTTP 401", "credentials")));
        AtomicBoolean called = new AtomicBoolean();
        NetmonCollector backsOffOnCredentials = new NetmonCollector() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public Duration cadence() {
                return Duration.ofMinutes(5);
            }

            @Override
            public boolean backsOffAfter(ErrorCode code) {
                return code == ErrorCode.CREDENTIALS || NetmonCollector.super.backsOffAfter(code);
            }

            @Override
            public void collect() {
                called.set(true);
            }
        };

        assertThat(runner(Map.of()).run(backsOffOnCredentials)).isFalse();
        assertThat(called).isFalse();
    }
}
