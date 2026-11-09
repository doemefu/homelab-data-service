package ch.furchert.homelab.data.webanalytics.ingest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The WA-5 ingest gauges (docs/100 §7.9). {@code webanalytics_ingest_capped} (1 from a capped batch until its
 * {@code retryAt}, evaluated at scrape time, so it returns to 0 without traffic) and {@code webanalytics_events_today}
 * are registered at startup. {@code webanalytics_raw_table_bytes} = {@code pg_total_relation_size} of
 * {@code webanalytics.events} (heap, indexes, TOAST), measured at startup and every 15 minutes; it is registered on
 * the first successful measurement (absent before, never NaN) and keeps its last value when a measurement fails.
 */
@Component
public class IngestGauges {

    private static final Logger log = LoggerFactory.getLogger(IngestGauges.class);

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final AtomicLong rawTableBytes = new AtomicLong();
    private volatile boolean rawTableBytesRegistered;

    public IngestGauges(MeterRegistry registry, IngestCaps caps, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("webanalytics.ingest.capped", caps, c -> c.capped() ? 1 : 0)
                .description("1 while analytics ingest is capped (until retryAt), else 0")
                .register(registry);
        Gauge.builder("webanalytics.events.today", caps, IngestCaps::eventsToday)
                .description("Accepted analytics events with received_at in the current UTC day")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        measureRawTable();
    }

    /** Every 15 minutes at :03/:18/:33/:48, off the collector minute marks. */
    @Scheduled(cron = "0 3/15 * * * *", zone = "UTC")
    public void measureRawTable() {
        try {
            Long bytes = jdbc.queryForObject("SELECT pg_total_relation_size('webanalytics.events')", Long.class);
            if (bytes == null) {
                return;
            }
            rawTableBytes.set(bytes);
            if (!rawTableBytesRegistered) {
                Gauge.builder("webanalytics.raw.table.bytes", rawTableBytes, AtomicLong::get)
                        .description("pg_total_relation_size of webanalytics.events (heap, indexes, TOAST)")
                        .register(registry);
                rawTableBytesRegistered = true;
            }
        } catch (DataAccessException e) {
            log.warn("[webanalytics] raw table size not measured: {}", e.getClass().getSimpleName());
        }
    }
}
