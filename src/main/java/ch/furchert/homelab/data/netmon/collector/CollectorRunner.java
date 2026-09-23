package ch.furchert.homelab.data.netmon.collector;

import ch.furchert.homelab.data.config.NetmonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes one collector run with the docs/060 §4.1 rules: kill switch
 * ({@code netmon.collectors.<name>.enabled}), no overlap (in-JVM {@link ReentrantLock#tryLock()};
 * data-service runs a single replica and every write is idempotent), and the failure rule (state
 * keeps its high-water mark, {@code consecutive_failures} increments, {@code last_error} is set).
 */
@Component
public class CollectorRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);
    static final int MAX_ERROR_MESSAGE_LENGTH = 200;

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
        if (!properties.isEnabled(name)) {
            log.debug("[{}] disabled, skipped", name);
            return false;
        }
        ReentrantLock lock = locks.computeIfAbsent(name, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("[{}] previous run still active, skipped", name);
            return false;
        }
        try {
            repository.recordAttempt(name, clock.instant());
            try {
                collector.collect();
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
