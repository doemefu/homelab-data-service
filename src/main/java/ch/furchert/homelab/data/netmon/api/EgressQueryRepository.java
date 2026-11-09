package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.config.RetentionProperties;
import ch.furchert.homelab.data.netmon.api.EgressDtos.EgressTopItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-side SQL for {@code /egress/top} (docs/060 §7.2). Windows are selected by overlap; rows are aggregated by
 * {@code (namespace, workload, container, destination_host, destination_port)} — {@code destination_host} is the IP,
 * or the name of a name-grouped destination — and ordered by total bytes.
 *
 * <p>data-service#57: a window starting at or after {@code now − rawDays} (with a grace of one hour for windows derived
 * from a slightly earlier clock) reads the hourly rows only.
 * An older window reads {@code egress_flow_daily} for every whole UTC day {@code d} with {@code d >= from} and
 * {@code d + 1 <= min(to, rawCutoff)}, and the hourly rows overlapping {@code [max(from, rawCutoff), to)}, where
 * {@code rawCutoff = start of today (UTC) − (rawDays − 1) d}; a partial first or last day before the cut-off is left
 * out. Every field keeps its meaning: {@code node} is the node with the most hourly rows (ties by name, like
 * {@code mode()}), {@code fqdn} the newest non-null.
 */
@Repository
public class EgressQueryRepository {

    /**
     * A window derived from an earlier "now" (furchert-ch computes {@code from = its now − 30 d}; the request reaches
     * data-service a moment later) still counts as inside the raw retention if it starts at most this long before
     * {@code now − rawDays}.
     */
    static final Duration RAW_GRACE = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final int rawDays;

    public EgressQueryRepository(JdbcClient jdbc, Clock clock, RetentionProperties retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rawDays = retention.egressFlowSnapshotsDays();
    }

    /**
     * @param scope     {@code external}, or null for all scopes
     * @param namespace exact match, or null
     * @param workload  exact match, or null
     */
    public List<EgressTopItem> top(TimeWindow window, String scope, String namespace, String workload, int limit) {
        Instant now = clock.instant();
        if (!window.from().isBefore(now.minus(Duration.ofDays(rawDays)).minus(RAW_GRACE))) {
            return topFromHourlyRows(window, scope, namespace, workload, limit);
        }
        Instant rawCutoff = startOfUtcDay(now).minus(Duration.ofDays(rawDays - 1L));
        LocalDate dailyFrom = firstWholeDay(window.from());
        LocalDate dailyTo = utcDay(window.to().isBefore(rawCutoff) ? window.to() : rawCutoff); // exclusive
        Instant rawFrom = window.from().isAfter(rawCutoff) ? window.from() : rawCutoff;
        return jdbc.sql("""
                        WITH rows AS (
                            SELECT s.namespace, s.workload, s.container, s.destination_host, s.destination_port, s.node,
                                   s.fqdn, s.window_start AS fqdn_window, s.destination_scope, s.bytes_sent,
                                   s.bytes_received, s.connects, s.failed_connects, s.window_start AS first_window,
                                   s.is_new, 1 AS hourly_rows
                            FROM netmon.egress_flow_snapshots s
                            WHERE s.window_start < :to AND s.window_end > :rawFrom
                              AND (CAST(:scope AS text) IS NULL OR s.destination_scope = CAST(:scope AS text))
                              AND (CAST(:namespace AS text) IS NULL OR s.namespace = CAST(:namespace AS text))
                              AND (CAST(:workload AS text) IS NULL OR s.workload = CAST(:workload AS text))
                            UNION ALL
                            SELECT d.namespace, d.workload, d.container, d.destination_host, d.destination_port, d.node,
                                   d.fqdn, d.fqdn_window, d.destination_scope, d.bytes_sent, d.bytes_received,
                                   d.connects, d.failed_connects, d.first_window, d.is_new, d.hourly_rows
                            FROM netmon.egress_flow_daily d
                            WHERE d.day >= :dailyFrom AND d.day < :dailyTo
                              AND (CAST(:scope AS text) IS NULL OR d.destination_scope = CAST(:scope AS text))
                              AND (CAST(:namespace AS text) IS NULL OR d.namespace = CAST(:namespace AS text))
                              AND (CAST(:workload AS text) IS NULL OR d.workload = CAST(:workload AS text))
                        ),
                        agg AS (
                            SELECT r.namespace, r.workload, r.container, r.destination_host, r.destination_port,
                                   (array_agg(r.fqdn ORDER BY r.fqdn_window DESC) FILTER (WHERE r.fqdn IS NOT NULL))[1] AS fqdn,
                                   min(r.destination_scope) AS scope,
                                   sum(r.bytes_sent) AS bytes_sent, sum(r.bytes_received) AS bytes_received,
                                   sum(r.connects) AS connects, sum(r.failed_connects) AS failed_connects,
                                   min(r.first_window) AS first_seen, bool_or(r.is_new) AS is_new
                            FROM rows r
                            GROUP BY r.namespace, r.workload, r.container, r.destination_host, r.destination_port
                        ),
                        nodes AS (
                            SELECT DISTINCT ON (r.namespace, r.workload, r.container, r.destination_host, r.destination_port)
                                   r.namespace, r.workload, r.container, r.destination_host, r.destination_port, r.node
                            FROM (SELECT namespace, workload, container, destination_host, destination_port, node,
                                         sum(hourly_rows) AS n
                                  FROM rows
                                  GROUP BY namespace, workload, container, destination_host, destination_port, node) r
                            ORDER BY r.namespace, r.workload, r.container, r.destination_host, r.destination_port,
                                     r.n DESC, r.node
                        )
                        SELECT a.namespace, a.workload, a.container, a.destination_host, a.destination_port, n.node,
                               a.fqdn, a.scope, a.bytes_sent, a.bytes_received, a.connects, a.failed_connects,
                               a.first_seen, a.is_new
                        FROM agg a
                        JOIN nodes n ON n.namespace IS NOT DISTINCT FROM a.namespace
                                    AND n.workload IS NOT DISTINCT FROM a.workload
                                    AND n.container IS NOT DISTINCT FROM a.container
                                    AND n.destination_host = a.destination_host
                                    AND n.destination_port = a.destination_port
                        ORDER BY a.bytes_sent + a.bytes_received DESC, a.connects DESC,
                                 a.namespace NULLS LAST, a.workload NULLS LAST, a.container NULLS LAST,
                                 a.destination_host, a.destination_port
                        LIMIT :limit
                        """)
                .param("to", utc(window.to()))
                .param("rawFrom", utc(rawFrom))
                .param("dailyFrom", dailyFrom)
                .param("dailyTo", dailyTo)
                .param("scope", scope)
                .param("namespace", namespace)
                .param("workload", workload)
                .param("limit", limit)
                .query((rs, i) -> item(rs))
                .list();
    }

    /** The hourly rows only: every window inside the raw retention (also used to compare in tests). */
    public List<EgressTopItem> topFromHourlyRows(TimeWindow window, String scope, String namespace, String workload,
                                                 int limit) {
        return jdbc.sql("""
                        SELECT s.namespace, s.workload, s.container, s.destination_host, s.destination_port,
                               mode() WITHIN GROUP (ORDER BY s.node)                                        AS node,
                               (array_agg(s.fqdn ORDER BY s.window_start DESC) FILTER (WHERE s.fqdn IS NOT NULL))[1] AS fqdn,
                               min(s.destination_scope)                                                     AS scope,
                               sum(s.bytes_sent)                                                            AS bytes_sent,
                               sum(s.bytes_received)                                                        AS bytes_received,
                               sum(s.connects)                                                              AS connects,
                               sum(s.failed_connects)                                                       AS failed_connects,
                               min(s.window_start)                                                          AS first_seen,
                               bool_or(s.is_new)                                                            AS is_new
                        FROM netmon.egress_flow_snapshots s
                        WHERE s.window_start < :to AND s.window_end > :from
                          AND (CAST(:scope AS text) IS NULL OR s.destination_scope = CAST(:scope AS text))
                          AND (CAST(:namespace AS text) IS NULL OR s.namespace = CAST(:namespace AS text))
                          AND (CAST(:workload AS text) IS NULL OR s.workload = CAST(:workload AS text))
                        GROUP BY s.namespace, s.workload, s.container, s.destination_host, s.destination_port
                        ORDER BY sum(s.bytes_sent) + sum(s.bytes_received) DESC, sum(s.connects) DESC,
                                 s.namespace NULLS LAST, s.workload NULLS LAST, s.container NULLS LAST,
                                 s.destination_host, s.destination_port
                        LIMIT :limit
                        """)
                .param("from", utc(window.from()))
                .param("to", utc(window.to()))
                .param("scope", scope)
                .param("namespace", namespace)
                .param("workload", workload)
                .param("limit", limit)
                .query((rs, i) -> item(rs))
                .list();
    }

    private static EgressTopItem item(ResultSet rs) throws SQLException {
        return new EgressTopItem(rs.getString("namespace"), rs.getString("workload"),
                rs.getString("container"), rs.getString("node"), rs.getString("destination_host"),
                rs.getInt("destination_port"), rs.getString("fqdn"), rs.getString("scope"),
                rs.getLong("bytes_sent"), rs.getLong("bytes_received"), rs.getLong("connects"),
                rs.getLong("failed_connects"), rs.getObject("first_seen", OffsetDateTime.class).toInstant(),
                rs.getBoolean("is_new"));
    }

    private static LocalDate utcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant startOfUtcDay(Instant instant) {
        return utcDay(instant).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The first UTC day that starts at or after {@code from}. */
    private static LocalDate firstWholeDay(Instant from) {
        LocalDate day = utcDay(from);
        return startOfUtcDay(from).equals(from) ? day : day.plusDays(1);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
