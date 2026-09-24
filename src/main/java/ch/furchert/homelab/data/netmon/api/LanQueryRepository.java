package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.LanDtos.ConnectionItem;
import ch.furchert.homelab.data.netmon.api.LanDtos.IpLan;
import ch.furchert.homelab.data.netmon.api.LanDtos.SshAuthItem;
import ch.furchert.homelab.data.netmon.api.LanDtos.UfwBlockItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * Read-side SQL for the LAN endpoints (docs/060 §7.2). Windows are selected by overlap
 * ({@code window_start < to AND window_end > from}); {@code node} and {@code dport} narrow when set.
 */
@Repository
public class LanQueryRepository {

    // Leading newline: text blocks strip the trailing space of the preceding "WHERE".
    private static final String WINDOW_AND_NODE = "\n" + """
            s.window_start < :to AND s.window_end > :from
              AND (CAST(:node AS text) IS NULL OR s.node = CAST(:node AS text))
            """;

    private final JdbcClient jdbc;

    public LanQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConnectionItem> connections(TimeWindow window, String node, Integer dport) {
        return bind(jdbc.sql("""
                        SELECT s.node, s.dport, s.src_ip, s.state,
                               max(s.peak_connections) AS peak,
                               count(*)                AS windows,
                               min(s.window_start)     AS first_seen,
                               max(s.window_end)       AS last_seen
                        FROM netmon.lan_connection_snapshots s
                        WHERE """ + WINDOW_AND_NODE + """
                          AND (CAST(:dport AS integer) IS NULL OR s.dport = CAST(:dport AS integer))
                        GROUP BY s.node, s.dport, s.src_ip, s.state
                        ORDER BY s.node, s.dport, peak DESC, s.src_ip, s.state
                        """), window, node)
                .param("dport", dport)
                .query((rs, i) -> new ConnectionItem(rs.getString("node"), rs.getInt("dport"), rs.getString("src_ip"),
                        rs.getString("state"), rs.getInt("peak"), rs.getLong("windows"), instant(rs, "first_seen"),
                        instant(rs, "last_seen")))
                .list();
    }

    public long ufwTotal(TimeWindow window, String node) {
        return bind(jdbc.sql("""
                        SELECT coalesce(sum(s.blocks), 0) AS blocks
                        FROM netmon.ufw_block_snapshots s
                        WHERE """ + WINDOW_AND_NODE), window, node)
                .query((rs, i) -> rs.getLong("blocks"))
                .single();
    }

    public List<UfwBlockItem> topUfwBlocks(TimeWindow window, String node, int limit) {
        return bind(jdbc.sql("""
                        SELECT s.src_ip, s.dport, s.proto, sum(s.blocks) AS blocks,
                               array_agg(DISTINCT s.node ORDER BY s.node) AS nodes
                        FROM netmon.ufw_block_snapshots s
                        WHERE """ + WINDOW_AND_NODE + """
                        GROUP BY s.src_ip, s.dport, s.proto
                        ORDER BY blocks DESC, s.src_ip, s.dport, s.proto
                        LIMIT :limit
                        """), window, node)
                .param("limit", limit)
                .query((rs, i) -> new UfwBlockItem(rs.getString("src_ip"), rs.getInt("dport"), rs.getString("proto"),
                        rs.getLong("blocks"), Arrays.asList((String[]) rs.getArray("nodes").getArray())))
                .list();
    }

    /** Per node and source, ordered by unsuccessful attempts (failed + invalid_user) within each node. */
    public List<SshAuthItem> sshAuth(TimeWindow window, String node) {
        return bind(jdbc.sql("""
                        SELECT s.node, s.src_ip,
                               coalesce(sum(s.attempts) FILTER (WHERE s.outcome = 'accepted'), 0)     AS accepted,
                               coalesce(sum(s.attempts) FILTER (WHERE s.outcome = 'failed'), 0)       AS failed,
                               coalesce(sum(s.attempts) FILTER (WHERE s.outcome = 'invalid_user'), 0) AS invalid_user
                        FROM netmon.ssh_auth_snapshots s
                        WHERE """ + WINDOW_AND_NODE + """
                        GROUP BY s.node, s.src_ip
                        ORDER BY s.node,
                                 coalesce(sum(s.attempts) FILTER (WHERE s.outcome IN ('failed', 'invalid_user')), 0) DESC,
                                 s.src_ip
                        """), window, node)
                .query((rs, i) -> new SshAuthItem(rs.getString("node"), rs.getString("src_ip"), rs.getLong("accepted"),
                        rs.getLong("failed"), rs.getLong("invalid_user")))
                .list();
    }

    /**
     * UFW blocks and unsuccessful SSH attempts of one IP. {@code src_ip} is text written by the node script
     * (Python's canonical form), so the IP is compared in Postgres' canonical form ({@code host(inet)}).
     */
    public IpLan ipSummary(TimeWindow window, String ip) {
        return jdbc.sql("""
                        SELECT
                          (SELECT coalesce(sum(u.blocks), 0) FROM netmon.ufw_block_snapshots u
                           WHERE u.src_ip = host(CAST(:ip AS inet))
                             AND u.window_start < :to AND u.window_end > :from) AS ufw_blocks,
                          (SELECT coalesce(sum(a.attempts), 0) FROM netmon.ssh_auth_snapshots a
                           WHERE a.src_ip = host(CAST(:ip AS inet)) AND a.outcome IN ('failed', 'invalid_user')
                             AND a.window_start < :to AND a.window_end > :from) AS ssh_failed
                        """)
                .param("ip", ip)
                .param("from", utc(window.from()))
                .param("to", utc(window.to()))
                .query((rs, i) -> new IpLan(rs.getLong("ufw_blocks"), rs.getLong("ssh_failed")))
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, TimeWindow window, String node) {
        return spec.param("from", utc(window.from()))
                .param("to", utc(window.to()))
                .param("node", node);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
