package ch.furchert.homelab.data.netmon.cloudflare;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

/** {@code netmon.firewall_events}; write mode "insert, do nothing on conflict" (docs/060 §3.3). */
@Repository
public class FirewallEventRepository {

    static final String SOURCE = "cloudflare-graphql";

    private static final String INSERT = """
            INSERT INTO netmon.firewall_events
                (occurred_at, ray_name, client_ip, country, asn, asn_org, action, security_source, rule_id,
                 host, method, path, user_agent, source)
            VALUES (:occurredAt, :rayName, CAST(:clientIp AS inet), :country, :asn, :asnOrg, :action,
                    :securitySource, :ruleId, :host, :method, :path, :userAgent, :source)
            ON CONFLICT (ray_name, security_source, (coalesce(rule_id, '')), action) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate named;

    public FirewallEventRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** Inserts the events; re-fetched events (overlap, keyset restarts) are absorbed by the natural key. */
    @Transactional
    public void insertAll(Collection<FirewallEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("occurredAt", OffsetDateTime.ofInstant(e.occurredAt(), ZoneOffset.UTC))
                        .addValue("rayName", e.rayName())
                        .addValue("clientIp", e.clientIp())
                        .addValue("country", e.country())
                        .addValue("asn", e.asn())
                        .addValue("asnOrg", e.asnOrg())
                        .addValue("action", e.action())
                        .addValue("securitySource", e.securitySource())
                        .addValue("ruleId", e.ruleId())
                        .addValue("host", e.host())
                        .addValue("method", e.method())
                        .addValue("path", e.path())
                        .addValue("userAgent", e.userAgent())
                        .addValue("source", SOURCE))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(INSERT, batch);
    }
}
