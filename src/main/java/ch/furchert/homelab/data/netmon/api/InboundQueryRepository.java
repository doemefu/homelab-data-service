package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.InboundDtos.AbuseIpDb;
import ch.furchert.homelab.data.netmon.api.InboundDtos.AsnCount;
import ch.furchert.homelab.data.netmon.api.InboundDtos.BlocklistHit;
import ch.furchert.homelab.data.netmon.api.InboundDtos.CountryCount;
import ch.furchert.homelab.data.netmon.api.InboundDtos.FirewallEventItem;
import ch.furchert.homelab.data.netmon.api.InboundDtos.HostCount;
import ch.furchert.homelab.data.netmon.api.InboundDtos.PathCount;
import ch.furchert.homelab.data.netmon.api.InboundDtos.StatusCount;
import ch.furchert.homelab.data.netmon.api.InboundDtos.TimelineBucket;
import ch.furchert.homelab.data.netmon.api.InboundDtos.TopClientIp;
import ch.furchert.homelab.data.netmon.api.InboundDtos.Totals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Read-side SQL for the inbound endpoints (docs/060 §7.2). Windowed request groups are selected by
 * overlap ({@code window_start < to AND window_end > from}); firewall events by {@code [from, to)}.
 * An optional {@code host} or {@code ip} narrows every aggregate the same way.
 */
@Repository
public class InboundQueryRepository {

    /** Filter on {@code inbound_request_groups r}; parameters {@code from}, {@code to}, {@code host}, {@code ip}. */
    // Leading newline: text blocks strip the trailing space of the preceding "WHERE".
    private static final String GROUPS_WHERE = "\n" + """
            r.window_start < :to AND r.window_end > :from
              AND (CAST(:host AS text) IS NULL OR r.host = CAST(:host AS text))
              AND (CAST(:ip AS inet) IS NULL OR r.client_ip = CAST(:ip AS inet))
            """;

    private final JdbcClient jdbc;

    public InboundQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The request-group filter shared by all aggregates. */
    public record GroupFilter(TimeWindow window, String host, String ip) {
    }

    public Totals totals(GroupFilter filter) {
        return bind(jdbc.sql("""
                        SELECT coalesce(sum(r.request_count), 0) AS requests,
                               count(DISTINCT r.client_ip)       AS ips,
                               coalesce(bool_or(r.sampled), false) AS sampled
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE), filter)
                .query((rs, i) -> new Totals(rs.getLong("requests"), rs.getLong("ips"), rs.getBoolean("sampled")))
                .single();
    }

    public List<TopClientIp> topClientIps(GroupFilter filter, int limit) {
        return bind(jdbc.sql("""
                        SELECT host(r.client_ip) AS ip,
                               sum(r.request_count) AS requests,
                               coalesce(e.country, max(r.country)) AS country,
                               coalesce(e.asn, max(r.asn))         AS asn,
                               coalesce(e.asn_org, max(r.asn_org)) AS asn_org,
                               coalesce(e.blocklisted, false)      AS blocklisted,
                               e.abuseipdb_score                   AS abuse_score,
                               (SELECT count(*) FROM netmon.firewall_events f
                                WHERE f.client_ip = r.client_ip AND f.occurred_at >= :from AND f.occurred_at < :to
                                  AND (CAST(:host AS text) IS NULL OR f.host = CAST(:host AS text)))
                                                                   AS firewall_events
                        FROM netmon.inbound_request_groups r
                        LEFT JOIN netmon.ip_enrichment e ON e.ip = r.client_ip
                        WHERE """ + GROUPS_WHERE + """
                        GROUP BY r.client_ip, e.ip
                        ORDER BY requests DESC, r.client_ip
                        LIMIT :limit
                        """), filter)
                .param("limit", limit)
                .query((rs, i) -> new TopClientIp(rs.getString("ip"), rs.getLong("requests"), rs.getString("country"),
                        integer(rs, "asn"), rs.getString("asn_org"), rs.getBoolean("blocklisted"),
                        integer(rs, "abuse_score"), rs.getLong("firewall_events")))
                .list();
    }

    public List<CountryCount> topCountries(GroupFilter filter, int limit) {
        return bind(jdbc.sql("""
                        SELECT r.country, sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                          AND r.country IS NOT NULL
                        GROUP BY r.country
                        ORDER BY requests DESC, r.country
                        LIMIT :limit
                        """), filter)
                .param("limit", limit)
                .query((rs, i) -> new CountryCount(rs.getString("country"), rs.getLong("requests")))
                .list();
    }

    public List<AsnCount> topAsns(GroupFilter filter, int limit) {
        return bind(jdbc.sql("""
                        SELECT r.asn, max(r.asn_org) AS asn_org, sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                          AND r.asn IS NOT NULL
                        GROUP BY r.asn
                        ORDER BY requests DESC, r.asn
                        LIMIT :limit
                        """), filter)
                .param("limit", limit)
                .query((rs, i) -> new AsnCount(integer(rs, "asn"), rs.getString("asn_org"), rs.getLong("requests")))
                .list();
    }

    public List<HostCount> topHosts(GroupFilter filter, int limit) {
        return bind(jdbc.sql("""
                        SELECT r.host, sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                        GROUP BY r.host
                        ORDER BY requests DESC, r.host
                        LIMIT :limit
                        """), filter)
                .param("limit", limit)
                .query((rs, i) -> new HostCount(rs.getString("host"), rs.getLong("requests")))
                .list();
    }

    public List<PathCount> topPaths(GroupFilter filter, int limit) {
        return bind(jdbc.sql("""
                        SELECT r.host, r.path, sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                        GROUP BY r.host, r.path
                        ORDER BY requests DESC, r.host, r.path
                        LIMIT :limit
                        """), filter)
                .param("limit", limit)
                .query((rs, i) -> new PathCount(rs.getString("host"), rs.getString("path"), rs.getLong("requests")))
                .list();
    }

    /** Every status in the window, ascending. */
    public List<StatusCount> statuses(GroupFilter filter) {
        return bind(jdbc.sql("""
                        SELECT r.status, sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                        GROUP BY r.status
                        ORDER BY r.status
                        """), filter)
                .query((rs, i) -> new StatusCount(rs.getInt("status"), rs.getLong("requests")))
                .list();
    }

    /** Buckets with data only, ascending; {@code unit} is {@code hour} or {@code day} (UTC). */
    public List<TimelineBucket> timeline(GroupFilter filter, String unit) {
        return bind(jdbc.sql("""
                        SELECT date_trunc(CAST(:unit AS text), r.window_start, 'UTC') AS bucket,
                               sum(r.request_count) AS requests
                        FROM netmon.inbound_request_groups r
                        WHERE """ + GROUPS_WHERE + """
                        GROUP BY bucket
                        ORDER BY bucket
                        """), filter)
                .param("unit", unit)
                .query((rs, i) -> new TimelineBucket(instant(rs, "bucket"), rs.getLong("requests")))
                .list();
    }

    /**
     * Firewall events in {@code [from, to)}, newest first, starting after {@code cursor}; returns up to
     * {@code limit} rows plus the keyset position of the last one.
     */
    public List<FirewallRow> firewallEvents(TimeWindow window, String action, String host, String ip,
                                            ApiParams.Cursor cursor, int limit) {
        return jdbc.sql("""
                        SELECT f.id, f.occurred_at, f.ray_name, host(f.client_ip) AS client_ip, f.country, f.asn,
                               f.asn_org, f.action, f.security_source, f.rule_id, f.host, f.method, f.path,
                               f.user_agent, coalesce(e.blocklisted, false) AS blocklisted
                        FROM netmon.firewall_events f
                        LEFT JOIN netmon.ip_enrichment e ON e.ip = f.client_ip
                        WHERE f.occurred_at >= :from AND f.occurred_at < :to
                          AND (CAST(:action AS text) IS NULL OR f.action = CAST(:action AS text))
                          AND (CAST(:host AS text) IS NULL OR f.host = CAST(:host AS text))
                          AND (CAST(:ip AS inet) IS NULL OR f.client_ip = CAST(:ip AS inet))
                          AND (CAST(:cursorAt AS timestamptz) IS NULL
                               OR (f.occurred_at, f.id) < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS bigint)))
                        ORDER BY f.occurred_at DESC, f.id DESC
                        LIMIT :limit
                        """)
                .param("from", utc(window.from()))
                .param("to", utc(window.to()))
                .param("action", action)
                .param("host", host)
                .param("ip", ip)
                .param("cursorAt", cursor == null ? null : utc(cursor.occurredAt()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("limit", limit)
                .query((rs, i) -> new FirewallRow(new ApiParams.Cursor(instant(rs, "occurred_at"), rs.getLong("id")),
                        new FirewallEventItem(instant(rs, "occurred_at"), rs.getString("ray_name"),
                                rs.getString("client_ip"), rs.getString("country"), integer(rs, "asn"),
                                rs.getString("asn_org"), rs.getString("action"), rs.getString("security_source"),
                                rs.getString("rule_id"), rs.getString("host"), rs.getString("method"),
                                rs.getString("path"), rs.getString("user_agent"), rs.getBoolean("blocklisted"))))
                .list();
    }

    /** One firewall row plus its keyset position. */
    public record FirewallRow(ApiParams.Cursor position, FirewallEventItem item) {
    }

    /** The enrichment row of one IP, without the blocklist hits. */
    public record Enrichment(String ip, Instant firstSeen, Instant lastSeen, List<String> seenIn, String country,
                             Integer asn, String asnOrg, AbuseIpDb abuseIpDb) {
    }

    public Optional<Enrichment> enrichment(String ip) {
        return jdbc.sql("""
                        SELECT host(ip) AS ip, first_seen, last_seen, seen_in, country, asn, asn_org,
                               abuseipdb_score, abuseipdb_reports, abuseipdb_checked_at
                        FROM netmon.ip_enrichment
                        WHERE ip = CAST(:ip AS inet)
                        """)
                .param("ip", ip)
                .query((rs, i) -> {
                    Instant checkedAt = instant(rs, "abuseipdb_checked_at");
                    // A per-IP check failure records checked_at without a score: still "not checked" for the API.
                    Integer score = integer(rs, "abuseipdb_score");
                    AbuseIpDb abuse = checkedAt == null || score == null ? null
                            : new AbuseIpDb(score, integer(rs, "abuseipdb_reports"), checkedAt);
                    String[] seenIn = (String[]) rs.getArray("seen_in").getArray();
                    return new Enrichment(rs.getString("ip"), instant(rs, "first_seen"), instant(rs, "last_seen"),
                            Arrays.stream(seenIn).sorted().toList(), rs.getString("country"), integer(rs, "asn"),
                            rs.getString("asn_org"), abuse);
                })
                .optional();
    }

    public List<BlocklistHit> blocklistHits(String ip) {
        return jdbc.sql("""
                        SELECT h ->> 'list' AS list, h ->> 'cidr' AS cidr, h ->> 'fetchedAt' AS fetched_at
                        FROM netmon.ip_enrichment, jsonb_array_elements(blocklist_hits) h
                        WHERE ip = CAST(:ip AS inet)
                        """)
                .param("ip", ip)
                .query((rs, i) -> new BlocklistHit(rs.getString("list"), rs.getString("cidr"),
                        Instant.parse(rs.getString("fetched_at"))))
                .list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, GroupFilter filter) {
        return spec.param("from", utc(filter.window().from()))
                .param("to", utc(filter.window().to()))
                .param("host", filter.host())
                .param("ip", filter.ip());
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
