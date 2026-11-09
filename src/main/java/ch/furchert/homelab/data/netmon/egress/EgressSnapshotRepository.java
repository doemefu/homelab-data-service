package ch.furchert.homelab.data.netmon.egress;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code netmon.egress_flow_snapshots}; write mode "replace per {@code window_start}" (docs/060 §3.3), plus the daily
 * aggregate {@code egress_flow_daily} (data-service#57): every replace recomputes the window's UTC day in the same
 * transaction. {@code is_new} is computed on insert: the row's {@code (workload_key, destination_host,
 * destination_port)} occurs neither in the hourly rows of the same UTC day before {@code window_start} nor in the
 * daily rows of the previous 30 UTC days. It only looks backwards, so re-collecting a window gives the same answer.
 * Days are always UTC days, computed in Java and bound as parameters, whatever the session time zone is.
 */
@Repository
class EgressSnapshotRepository {

    static final String SOURCE = "prometheus-coroot";
    static final Duration WINDOW = Duration.ofHours(1);
    static final int LOOKBACK_DAYS = 30;

    private static final String INSERT = """
            INSERT INTO netmon.egress_flow_snapshots
                (window_start, window_end, node, container_id, namespace, pod, container, workload, workload_key,
                 destination, actual_destination, destination_ip, destination_port, destination_scope, fqdn,
                 bytes_sent, bytes_received, connects, failed_connects, is_new, source)
            VALUES (:windowStart, :windowEnd, :node, :containerId, :namespace, :pod, :container, :workload, :workloadKey,
                    :destination, :actualDestination, CAST(:destinationIp AS inet), :port, :scope, :fqdn,
                    :bytesSent, :bytesReceived, :connects, :failedConnects,
                    NOT EXISTS (SELECT 1 FROM netmon.egress_flow_snapshots p
                                WHERE p.workload_key = :workloadKey
                                  AND p.destination_host = :host
                                  AND p.destination_port = :port
                                  AND p.window_start < :windowStart
                                  AND p.window_start >= :dayStart)
                    AND NOT EXISTS (SELECT 1 FROM netmon.egress_flow_daily d
                                    WHERE d.workload_key = :workloadKey
                                      AND d.destination_host = :host
                                      AND d.destination_port = :port
                                      AND d.day >= :lookbackDay
                                      AND d.day < :day),
                    :source)
            """;

    private static final String RECOMPUTE_DAY = """
            INSERT INTO netmon.egress_flow_daily
                (day, node, workload_key, namespace, workload, container, destination_host, destination_port,
                 destination_scope, fqdn, fqdn_window, bytes_sent, bytes_received, connects, failed_connects,
                 first_window, is_new, hourly_rows)
            SELECT :day, s.node, s.workload_key, max(s.namespace), max(s.workload), max(s.container), s.destination_host,
                   s.destination_port, s.destination_scope,
                   (array_agg(s.fqdn ORDER BY s.window_start DESC) FILTER (WHERE s.fqdn IS NOT NULL))[1],
                   max(s.window_start) FILTER (WHERE s.fqdn IS NOT NULL),
                   sum(s.bytes_sent), sum(s.bytes_received), sum(s.connects), sum(s.failed_connects),
                   min(s.window_start), bool_or(s.is_new), count(*)
            FROM netmon.egress_flow_snapshots s
            WHERE s.window_start >= :from AND s.window_start < :to
            GROUP BY s.node, s.workload_key, s.destination_host, s.destination_port, s.destination_scope
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    EgressSnapshotRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Replaces the whole window and recomputes its UTC day in one transaction; an empty list leaves the window with 0
     * rows (and the day without them).
     */
    @Transactional
    void replace(Instant windowStart, List<EgressFlow> flows) {
        jdbc.sql("DELETE FROM netmon.egress_flow_snapshots WHERE window_start = :start")
                .param("start", utc(windowStart))
                .update();
        LocalDate day = utcDay(windowStart);
        if (!flows.isEmpty()) {
            insert(windowStart, day, flows);
        }
        recomputeDay(day);
    }

    private void insert(Instant windowStart, LocalDate day, List<EgressFlow> flows) {
        SqlParameterSource[] batch = flows.stream()
                .map(f -> new MapSqlParameterSource()
                        .addValue("windowStart", utc(windowStart))
                        .addValue("windowEnd", utc(windowStart.plus(WINDOW)))
                        .addValue("node", f.node())
                        .addValue("containerId", f.containerId())
                        .addValue("namespace", f.identity().namespace())
                        .addValue("pod", f.identity().pod())
                        .addValue("container", f.identity().container())
                        .addValue("workload", f.identity().workload())
                        .addValue("workloadKey", f.identity().workloadKey())
                        .addValue("destination", f.destination())
                        .addValue("actualDestination", f.actualDestination())
                        .addValue("destinationIp", f.target().ip())
                        .addValue("port", f.target().port())
                        .addValue("host", f.target().host())
                        .addValue("scope", f.scope())
                        .addValue("fqdn", f.fqdn())
                        .addValue("bytesSent", f.bytesSent())
                        .addValue("bytesReceived", f.bytesReceived())
                        .addValue("connects", f.connects())
                        .addValue("failedConnects", f.failedConnects())
                        .addValue("source", SOURCE)
                        .addValue("dayStart", utc(startOf(day)))
                        .addValue("day", day)
                        .addValue("lookbackDay", day.minusDays(LOOKBACK_DAYS)))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(INSERT, batch);
    }

    /**
     * Rebuilds the daily rows of one UTC day from its hourly rows (joins the caller's transaction, else its own). Rows
     * are grouped by the primary key; {@code namespace}, {@code workload} and {@code container} follow from
     * {@code workload_key} and are taken with {@code max()}, so a row that disagrees can never break the key. An
     * advisory lock per day serialises concurrent recomputes of the same day; {@code work_mem} and
     * {@code statement_timeout} are set for this transaction only.
     */
    @Transactional
    public void recomputeDay(LocalDate day) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('netmon-egress-daily'), :day)::text")
                .param("day", (int) day.toEpochDay()).query(String.class).single();
        jdbc.sql("SET LOCAL work_mem = '16MB'").update();
        jdbc.sql("SET LOCAL statement_timeout = '60s'").update();
        jdbc.sql("DELETE FROM netmon.egress_flow_daily WHERE day = :day").param("day", day).update();
        jdbc.sql(RECOMPUTE_DAY).param("day", day).param("from", utc(startOf(day)))
                .param("to", utc(startOf(day.plusDays(1)))).update();
    }

    /** The oldest {@code window_start} still held, or null (backward/forward index scan). */
    Instant oldestWindow() {
        return jdbc.sql("SELECT min(window_start) FROM netmon.egress_flow_snapshots")
                .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }

    /** Whether the UTC day has hourly rows (index range probe). */
    boolean hasHourlyRows(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM netmon.egress_flow_snapshots
                                       WHERE window_start >= :from AND window_start < :to)
                        """).param("from", utc(startOf(day))).param("to", utc(startOf(day.plusDays(1))))
                .query(Boolean.class).single());
    }

    /** Whether the UTC day has daily rows (primary-key prefix probe). */
    boolean hasDailyRows(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM netmon.egress_flow_daily WHERE day = :day)")
                .param("day", day).query(Boolean.class).single());
    }

    /** Whether the day's hourly rows were written after its daily rows were last computed. */
    boolean isStale(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT (SELECT max(ingested_at) FROM netmon.egress_flow_snapshots
                                WHERE window_start >= :from AND window_start < :to)
                             > (SELECT max(computed_at) FROM netmon.egress_flow_daily WHERE day = :day)
                        """).param("from", utc(startOf(day))).param("to", utc(startOf(day.plusDays(1))))
                .param("day", day).query(Boolean.class).optional().orElse(false));
    }

    static LocalDate utcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
