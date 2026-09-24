package ch.furchert.homelab.data.netmon.lan;

import ch.furchert.homelab.data.netmon.lan.LanRows.Connection;
import ch.furchert.homelab.data.netmon.lan.LanRows.SshAuth;
import ch.furchert.homelab.data.netmon.lan.LanRows.UfwBlock;
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
import java.util.function.Function;

/**
 * The three NM-3 tables; write mode "replace per {@code (window_start, node)}" (docs/060 §3.3). An empty
 * row list still deletes the slice, so a node that published an empty bucket ends up with 0 rows rather
 * than keeping a previous run's values.
 */
@Repository
class LanSnapshotRepository {

    static final String SOURCE = "prometheus-textfile";
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    LanSnapshotRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Replaces one node's slice of a window in one transaction. {@code ufw}/{@code ssh} {@code null} leaves
     * those tables untouched (the node has not published the bucket for this window).
     */
    @Transactional
    void replace(Instant windowStart, String node, List<Connection> connections, List<UfwBlock> ufw,
                 List<SshAuth> ssh) {
        if (connections != null) {
            replaceSlice("lan_connection_snapshots", windowStart, node, connections, """
                    INSERT INTO netmon.lan_connection_snapshots
                        (window_start, window_end, node, dport, src_ip, state, peak_connections, source)
                    VALUES (:windowStart, :windowEnd, :node, :dport, :srcIp, :state, :value, :source)
                    """, c -> new MapSqlParameterSource()
                    .addValue("dport", c.dport())
                    .addValue("srcIp", c.srcIp())
                    .addValue("state", c.state())
                    .addValue("value", c.peakConnections()));
        }
        if (ufw != null) {
            replaceSlice("ufw_block_snapshots", windowStart, node, ufw, """
                    INSERT INTO netmon.ufw_block_snapshots
                        (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                    VALUES (:windowStart, :windowEnd, :node, :srcIp, :dport, :proto, :value, :source)
                    """, u -> new MapSqlParameterSource()
                    .addValue("srcIp", u.srcIp())
                    .addValue("dport", u.dport())
                    .addValue("proto", u.proto())
                    .addValue("value", u.blocks()));
        }
        if (ssh != null) {
            replaceSlice("ssh_auth_snapshots", windowStart, node, ssh, """
                    INSERT INTO netmon.ssh_auth_snapshots
                        (window_start, window_end, node, src_ip, outcome, attempts, source)
                    VALUES (:windowStart, :windowEnd, :node, :srcIp, :outcome, :value, :source)
                    """, s -> new MapSqlParameterSource()
                    .addValue("srcIp", s.srcIp())
                    .addValue("outcome", s.outcome())
                    .addValue("value", s.attempts()));
        }
    }

    /** {@code table} is one of the three fixed names above, never input. */
    private <T> void replaceSlice(String table, Instant windowStart, String node, List<T> rows, String insert,
                                  Function<T, MapSqlParameterSource> columns) {
        jdbc.sql("DELETE FROM netmon." + table + " WHERE window_start = :start AND node = :node")
                .param("start", utc(windowStart))
                .param("node", node)
                .update();
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream()
                .map(row -> columns.apply(row)
                        .addValue("windowStart", utc(windowStart))
                        .addValue("windowEnd", utc(windowStart.plus(WINDOW)))
                        .addValue("node", node)
                        .addValue("source", SOURCE))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(insert, batch);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
