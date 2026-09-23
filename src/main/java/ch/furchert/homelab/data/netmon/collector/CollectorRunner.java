package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes one collector run with the docs/060 §4.1 rules: kill switch
 * ({@code netmon.collectors.<name>.enabled}), no overlap (in-JVM {@link ReentrantLock#tryLock()};
 * data-service runs a single replica and every write is idempotent), and the failure rule (state
 * keeps its high-water mark, {@code consecutive_failures} increments, {@code last_error} is set).
 * After {@code rate_limited}/{@code upstream} failures, runs are spaced by an exponential backoff of
 * {@code min(cadence * 2^(failures-1), 30 min)}; a {@link CollectorWarning} counts as a success whose
 * code and message are kept for the status API.
 */
@Component
public class CollectorRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);
    static final int MAX_ERROR_MESSAGE_LENGTH = 200;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    /** Cron fires at the exact cadence while the attempt is stamped a little later; tolerate that. */
    static final Duration BACKOFF_TOLERANCE = Duration.ofSeconds(30);

    private final CollectorStateRepository repository;
    private final NetmonProperties properties;
    private final CollectorMetrics metrics;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CollectorRunner(CollectorStateRepository repository, NetmonProperties properties,
                           CollectorMetrics metrics, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @return {@code true} if the run completed successfully; {@code false} if it was disabled,
     * skipped because a previous run is still active, or failed
     */
    public boolean run(NetmonCollector collector) {
        String name = collector.name();
        if (!properties.isEnabled(name) || !collector.available()) {
            log.debug("[{}] disabled, skipped", name);
            return false;
        }
        ReentrantLock lock = locks.computeIfAbsent(name, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("[{}] previous run still active, skipped", name);
            return false;
        }
        try {
            Instant now = clock.instant();
            if (inBackoff(collector, now)) {
                log.info("[{}] backing off after upstream failures, skipped", name);
                return false;
            }
            repository.recordAttempt(name, now);
            try {
                collector.collect();
            } catch (CollectorWarning w) {
                Instant finishedAt = clock.instant();
                String warning = describe(w);
                repository.recordSuccess(name, finishedAt, w.code(), warning);
                metrics.markSuccess(name, finishedAt);
                log.warn("[{}] run completed with warning: code={} warning={}", name, w.code().value(), warning);
                return true;
            } catch (Exception e) {
                ErrorCode code = e instanceof CollectorException ce ? ce.code() : ErrorCode.INTERNAL;
                String error = describe(e);
                repository.recordFailure(name, code, error);
                log.warn("[{}] run failed: code={} error={}", name, code.value(), error);
                return false;
            }
            var finishedAt = clock.instant();
            repository.recordSuccess(name, finishedAt);
            metrics.markSuccess(name, finishedAt);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean inBackoff(NetmonCollector collector, Instant now) {
        CollectorState state = repository.find(collector.name()).orElse(null);
        if (state == null || state.consecutiveFailures() == 0 || state.lastAttemptAt() == null
                || !(ErrorCode.RATE_LIMITED.value().equals(state.lastErrorCode())
                || ErrorCode.UPSTREAM.value().equals(state.lastErrorCode()))) {
            return false;
        }
        Duration backoff = backoff(collector.cadence(), state.consecutiveFailures());
        return now.isBefore(state.lastAttemptAt().plus(backoff).minus(BACKOFF_TOLERANCE));
    }

    /** {@code min(cadence * 2^(failures-1), 30 min)}; the first failure keeps the normal cadence. */
    static Duration backoff(Duration cadence, int failures) {
        int exponent = Math.min(Math.max(failures - 1, 0), 16);
        Duration backoff = cadence.multipliedBy(1L << exponent);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /**
     * Only {@link CollectorException} messages are collector-authored and therefore safe to keep;
     * any other exception (SQL, HTTP client, ...) may carry URLs, parameters or row data, so only
     * its class name is recorded.
     */
    static String describe(Exception e) {
        String type = e.getClass().getSimpleName();
        if (e instanceof CollectorException && e.getMessage() != null) {
            String message = e.getMessage();
            if (message.length() > MAX_ERROR_MESSAGE_LENGTH) {
                message = message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
            }
            return type + ": " + message;
        }
        return type;
    }
}
