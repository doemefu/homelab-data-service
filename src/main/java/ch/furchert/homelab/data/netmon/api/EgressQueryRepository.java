package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.EgressDtos.EgressTopItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-side SQL for {@code /egress/top} (docs/060 §7.2). Windows are selected by overlap; rows are aggregated by
 * {@code (namespace, workload, container, destination_host, destination_port)} — {@code destination_host} is the IP,
 * or the name of a name-grouped destination — and ordered by total bytes.
 */
@Repository
public class EgressQueryRepository {

    private final JdbcClient jdbc;

    public EgressQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param scope     {@code external}, or null for all scopes
     * @param namespace exact match, or null
     * @param workload  exact match, or null
     */
    public List<EgressTopItem> top(TimeWindow window, String scope, String namespace, String workload, int limit) {
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
                .query((rs, i) -> new EgressTopItem(rs.getString("namespace"), rs.getString("workload"),
                        rs.getString("container"), rs.getString("node"), rs.getString("destination_host"),
                        rs.getInt("destination_port"), rs.getString("fqdn"), rs.getString("scope"),
                        rs.getLong("bytes_sent"), rs.getLong("bytes_received"), rs.getLong("connects"),
                        rs.getLong("failed_connects"), rs.getObject("first_seen", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("is_new")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
