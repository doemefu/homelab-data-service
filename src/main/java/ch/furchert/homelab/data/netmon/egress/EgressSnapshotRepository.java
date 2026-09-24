package ch.furchert.homelab.data.netmon.egress;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code netmon.egress_flow_snapshots}; write mode "replace per {@code window_start}" (docs/060 §3.3). {@code is_new}
 * is computed on insert: the row's {@code (workload_key, destination_host, destination_port)} does not occur in the
 * 30 days before {@code window_start}. It only looks backwards, so re-collecting a window gives the same answer.
 */
@Repository
class EgressSnapshotRepository {

    static final String SOURCE = "prometheus-coroot";
    static final Duration WINDOW = Duration.ofHours(1);

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
                                  AND p.window_start >= CAST(:windowStart AS timestamptz) - interval '30 days'),
                    :source)
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    EgressSnapshotRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** Replaces the whole window in one transaction; an empty list leaves the window with 0 rows. */
    @Transactional
    void replace(Instant windowStart, List<EgressFlow> flows) {
        jdbc.sql("DELETE FROM netmon.egress_flow_snapshots WHERE window_start = :start")
                .param("start", utc(windowStart))
                .update();
        if (flows.isEmpty()) {
            return;
        }
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
                        .addValue("source", SOURCE))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(INSERT, batch);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
