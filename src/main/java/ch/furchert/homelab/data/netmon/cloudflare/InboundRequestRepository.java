package ch.furchert.homelab.data.netmon.cloudflare;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code netmon.inbound_request_groups}; write mode "replace per window" (docs/060 §3.3). */
@Repository
public class InboundRequestRepository {

    static final String SOURCE = "cloudflare-graphql";

    private static final String INSERT = """
            INSERT INTO netmon.inbound_request_groups
                (window_start, window_end, is_final, client_ip, country, asn, asn_org, host, method, path,
                 status, request_count, sample_interval, source)
            VALUES (:windowStart, :windowEnd, :isFinal, CAST(:clientIp AS inet), :country, :asn, :asnOrg, :host,
                    :method, :path, :status, :requestCount, :sampleInterval, :source)
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public InboundRequestRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Replaces the hour starting at {@code windowStart} in one transaction. {@code groups} must already
     * be aggregated per natural key ({@link #aggregate(Collection)}).
     */
    @Transactional
    public void replaceWindow(Instant windowStart, Instant windowEnd, boolean isFinal, Collection<RequestGroup> groups) {
        jdbc.sql("DELETE FROM netmon.inbound_request_groups WHERE window_start = :start")
                .param("start", utc(windowStart))
                .update();
        if (groups.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = groups.stream()
                .map(g -> new MapSqlParameterSource()
                        .addValue("windowStart", utc(windowStart))
                        .addValue("windowEnd", utc(windowEnd))
                        .addValue("isFinal", isFinal)
                        .addValue("clientIp", g.clientIp())
                        .addValue("country", g.country())
                        .addValue("asn", g.asn())
                        .addValue("asnOrg", g.asnOrg())
                        .addValue("host", g.host())
                        .addValue("method", g.method())
                        .addValue("path", g.path())
                        .addValue("status", (short) g.status())
                        .addValue("requestCount", g.count())
                        .addValue("sampleInterval", (float) g.sampleInterval())
                        .addValue("source", SOURCE))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(INSERT, batch);
    }

    /**
     * Sums groups per natural key {@code (client_ip, host, method, path, status)}. Paths are already
     * truncated by the client, so two raw paths that collapse to one key are merged here instead of
     * violating the UNIQUE constraint (docs/060 §3.2). The merged {@code sample_interval} is the
     * maximum, so a merged group is marked sampled if any part was.
     */
    public static List<RequestGroup> aggregate(Collection<RequestGroup> groups) {
        Map<List<Object>, RequestGroup> merged = new LinkedHashMap<>();
        for (RequestGroup g : groups) {
            List<Object> key = List.of(g.clientIp(), g.host(), g.method(), g.path(), g.status());
            merged.merge(key, g, (a, b) -> new RequestGroup(a.clientIp(),
                    a.country() != null ? a.country() : b.country(),
                    a.asn() != null ? a.asn() : b.asn(),
                    a.asnOrg() != null ? a.asnOrg() : b.asnOrg(),
                    a.host(), a.method(), a.path(), a.status(),
                    a.count() + b.count(),
                    Math.max(a.sampleInterval(), b.sampleInterval())));
        }
        return List.copyOf(merged.values());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
