package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Records a capped batch in {@code daily_ingest_stats(day, 'capped', scope)} (Zurich day of receipt; the source of
 * {@code /coverage.dataQuality.cappedDays}) in its own short transaction, after the batch transaction rolled back.
 * A failure is logged with the class name only and never changes the 429 answer.
 */
@Component
class CappedBatchRecorder {

    private static final Logger log = LoggerFactory.getLogger(CappedBatchRecorder.class);

    private final WebAnalyticsRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ZoneId zone;

    CappedBatchRecorder(WebAnalyticsRepository repository, PlatformTransactionManager transactionManager, Clock clock,
                        WebAnalyticsProperties properties) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.zone = properties.timezone();
    }

    void record(IngestCaps.Capped capped) {
        try {
            transactions.executeWithoutResult(status -> {
                repository.applyStatementTimeout();
                repository.incrementCapped(LocalDate.ofInstant(clock.instant(), zone), capped.scope().label(),
                        capped.events());
            });
        } catch (RuntimeException e) {
            log.warn("[webanalytics] capped batch not recorded: {}", e.getClass().getSimpleName());
        }
    }
}
