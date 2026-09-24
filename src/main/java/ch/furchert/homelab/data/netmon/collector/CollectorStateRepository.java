package ch.furchert.homelab.data.netmon.collector;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Upsert-only access to {@code netmon.collector_state}; a row is created on a collector's first run. */
@Repository
public class CollectorStateRepository {

    private final JdbcClient jdbc;

    public CollectorStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CollectorState> findAll() {
        return jdbc.sql("""
                        SELECT collector, last_window_end, cursor, last_attempt_at, last_success_at,
                               consecutive_failures, last_error, last_error_code
                        FROM netmon.collector_state
                        """)
                .query(CollectorStateRepository::mapRow)
                .list();
    }

    public Optional<CollectorState> find(String collector) {
        return jdbc.sql("""
                        SELECT collector, last_window_end, cursor, last_attempt_at, last_success_at,
                               consecutive_failures, last_error, last_error_code
                        FROM netmon.collector_state
                        WHERE collector = :collector
                        """)
                .param("collector", collector)
                .query(CollectorStateRepository::mapRow)
                .optional();
    }

    /** Advances (or sets) the high-water mark; called by a collector as it completes windows. */
    public void updateWindowEnd(String collector, Instant windowEnd) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state (collector, last_window_end) VALUES (:collector, :end)
                        ON CONFLICT (collector) DO UPDATE SET last_window_end = EXCLUDED.last_window_end
                        """)
                .param("collector", collector)
                .param("end", utc(windowEnd))
                .update();
    }

    public void updateCursor(String collector, String cursor) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state (collector, cursor) VALUES (:collector, :cursor)
                        ON CONFLICT (collector) DO UPDATE SET cursor = EXCLUDED.cursor
                        """)
                .param("collector", collector)
                .param("cursor", cursor)
                .update();
    }

    public void recordAttempt(String collector, Instant at) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state (collector, last_attempt_at) VALUES (:collector, :at)
                        ON CONFLICT (collector) DO UPDATE SET last_attempt_at = EXCLUDED.last_attempt_at
                        """)
                .param("collector", collector)
                .param("at", utc(at))
                .update();
    }

    public void recordSuccess(String collector, Instant at) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state (collector, last_success_at, consecutive_failures)
                        VALUES (:collector, :at, 0)
                        ON CONFLICT (collector) DO UPDATE SET
                            last_success_at = EXCLUDED.last_success_at,
                            consecutive_failures = 0,
                            last_error = NULL,
                            last_error_code = NULL
                        """)
                .param("collector", collector)
                .param("at", utc(at))
                .update();
    }

    /** A successful run that still reports a warning code (e.g. {@code truncated}) to the status API. */
    public void recordSuccess(String collector, Instant at, ErrorCode warningCode, String warning) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state
                            (collector, last_success_at, consecutive_failures, last_error, last_error_code)
                        VALUES (:collector, :at, 0, :error, :code)
                        ON CONFLICT (collector) DO UPDATE SET
                            last_success_at = EXCLUDED.last_success_at,
                            consecutive_failures = 0,
                            last_error = EXCLUDED.last_error,
                            last_error_code = EXCLUDED.last_error_code
                        """)
                .param("collector", collector)
                .param("at", utc(at))
                .param("error", warning)
                .param("code", warningCode.value())
                .update();
    }

    public void recordFailure(String collector, ErrorCode code, String error) {
        jdbc.sql("""
                        INSERT INTO netmon.collector_state (collector, consecutive_failures, last_error, last_error_code)
                        VALUES (:collector, 1, :error, :code)
                        ON CONFLICT (collector) DO UPDATE SET
                            consecutive_failures = netmon.collector_state.consecutive_failures + 1,
                            last_error = EXCLUDED.last_error,
                            last_error_code = EXCLUDED.last_error_code
                        """)
                .param("collector", collector)
                .param("error", error)
                .param("code", code.value())
                .update();
    }

    private static CollectorState mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CollectorState(
                rs.getString("collector"),
                instant(rs, "last_window_end"),
                rs.getString("cursor"),
                instant(rs, "last_attempt_at"),
                instant(rs, "last_success_at"),
                rs.getInt("consecutive_failures"),
                rs.getString("last_error"),
                rs.getString("last_error_code"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
