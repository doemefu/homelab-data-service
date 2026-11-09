package ch.furchert.homelab.data.webanalytics.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * Erasure of one visitor id (docs/100 §6.5; WA-5 plan ruling R2): (1) tombstone first, so no new batch can insert the
 * id; (2) a barrier transaction takes the exclusive side of the ingest lock (lock_timeout 1 s) and commits at once,
 * which waits for batches already in flight (they hold the shared side) but never holds the lock across the deletes,
 * so parallel erasures only meet at the barrier; (3) batched deletes ({@value #BATCH_SIZE} rows per statement and
 * transaction) until none are left. The per-request bound of 2.5 s is checked before every batch, and each batch's
 * statement timeout is the remaining time (at most 2 s, at least 100 ms). Progress is
 * committed per batch; an incomplete erasure (bound elapsed, barrier lock timeout, statement timeout) throws
 * {@link VisitorEraseIncompleteException} and the client's retry finishes it. Logs the row count only, never the id.
 */
@Service
public class VisitorEraseService {

    static final int BATCH_SIZE = 5_000;
    static final Duration BOUND = Duration.ofMillis(2_500);
    static final long MAX_STATEMENT_TIMEOUT_MS = 2_000;
    static final long MIN_STATEMENT_TIMEOUT_MS = 100;
    private static final Logger log = LoggerFactory.getLogger(VisitorEraseService.class);

    private final WebAnalyticsRepository repository;
    private final Tombstones tombstones;
    private final IngestCaps caps;
    private final TransactionTemplate transactions;

    public VisitorEraseService(WebAnalyticsRepository repository, Tombstones tombstones, IngestCaps caps,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tombstones = tombstones;
        this.caps = caps;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @return the number of rows deleted by this call */
    public long erase(UUID visitorId) {
        long started = System.nanoTime();
        tombstones.add(visitorId);
        try {
            return eraseRows(visitorId, started);
        } finally {
            // After the barrier and the deletes: a batch that committed while the barrier waited may have charged the
            // visitor's per-visitor count; the key goes so the next sight re-seeds it from the (now empty) table.
            caps.forgetVisitor(visitorId);
        }
    }

    private long eraseRows(UUID visitorId, long started) {
        try {
            transactions.executeWithoutResult(status -> repository.eraseBarrier());
        } catch (DataAccessException e) {
            if (!isTimeout(e)) {
                throw e;
            }
            log.warn("[webanalytics] visitor erase incomplete rows=0 (barrier lock timeout)");
            throw new VisitorEraseIncompleteException();
        }
        long total = 0;
        while (true) {
            // The bound is checked before every batch, and a batch may only use the remaining time (floor 100 ms),
            // so a response stays within about 2.5 s (furchert-ch's attempt timeout is 3 s).
            long remainingMs = BOUND.minus(Duration.ofNanos(System.nanoTime() - started)).toMillis();
            if (remainingMs <= 0) {
                log.warn("[webanalytics] visitor erase incomplete rows={}", total);
                throw new VisitorEraseIncompleteException();
            }
            long timeoutMs = Math.max(MIN_STATEMENT_TIMEOUT_MS, Math.min(MAX_STATEMENT_TIMEOUT_MS, remainingMs));
            Integer deleted;
            try {
                deleted = transactions.execute(status ->
                        repository.deleteVisitorBatch(visitorId, BATCH_SIZE, timeoutMs));
            } catch (DataAccessException e) {
                if (!isTimeout(e)) {
                    throw e;
                }
                log.warn("[webanalytics] visitor erase incomplete rows={} (statement timeout)", total);
                throw new VisitorEraseIncompleteException();
            }
            total += deleted == null ? 0 : deleted;
            if (deleted == null || deleted < BATCH_SIZE) {
                break;
            }
        }
        log.info("[webanalytics] visitor erased rows={}", total);
        return total;
    }

    /** PostgreSQL lock timeout (55P03) or statement timeout (57014), however Spring categorised it. */
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState())
                    || "57014".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
