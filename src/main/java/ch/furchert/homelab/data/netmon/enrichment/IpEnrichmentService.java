package ch.furchert.homelab.data.netmon.enrichment;

import ch.furchert.homelab.data.netmon.ip.PublicIpFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maintains {@code netmon.ip_enrichment} (docs/060 §4.3, §4.4): after every data-set write, each distinct
 * public IP is upserted (first/last seen, {@code seen_in}, latest Cloudflare geo/ASN) and matched against
 * the current blocklist entries. Non-public addresses are dropped before anything is written.
 */
@Service
public class IpEnrichmentService {

    public static final Set<String> DATASETS = Set.of("inbound", "firewall", "login", "lan", "egress");

    private static final String UPSERT = """
            INSERT INTO netmon.ip_enrichment AS e
                (ip, first_seen, last_seen, seen_in, country, asn, asn_org, source)
            VALUES (CAST(:ip AS inet), :firstSeen, :lastSeen, ARRAY[CAST(:dataset AS text)], :country, :asn, :asnOrg, :source)
            ON CONFLICT (ip) DO UPDATE SET
                first_seen = LEAST(e.first_seen, EXCLUDED.first_seen),
                last_seen  = GREATEST(e.last_seen, EXCLUDED.last_seen),
                seen_in    = CASE WHEN e.seen_in @> EXCLUDED.seen_in THEN e.seen_in
                                  ELSE e.seen_in || EXCLUDED.seen_in END,
                country    = COALESCE(EXCLUDED.country, e.country),
                asn        = COALESCE(EXCLUDED.asn, e.asn),
                asn_org    = COALESCE(EXCLUDED.asn_org, e.asn_org),
                source     = EXCLUDED.source
            """;

    /**
     * Recomputes {@code blocklist_hits}/{@code blocklisted} for the rows selected by {@code %s} (alias
     * {@code x}); rows whose hits did not change are not rewritten.
     */
    private static final String MATCH_TEMPLATE = """
            UPDATE netmon.ip_enrichment i
            SET blocklist_hits = h.hits,
                blocklisted    = jsonb_array_length(h.hits) > 0
            FROM (
                SELECT x.ip,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'list', b.list_name,
                                      'cidr', b.cidr::text,
                                      'fetchedAt', to_char(s.fetched_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
                                  ORDER BY b.list_name, b.cidr)
                           FROM netmon.blocklist_entries b
                           JOIN netmon.blocklist_snapshots s ON s.id = b.snapshot_id
                           WHERE b.cidr >>= x.ip), '[]'::jsonb) AS hits
                FROM netmon.ip_enrichment x
                WHERE %s
            ) h
            WHERE i.ip = h.ip AND i.blocklist_hits IS DISTINCT FROM h.hits
            """;

    private final NamedParameterJdbcTemplate named;
    private final JdbcClient jdbc;

    public IpEnrichmentService(NamedParameterJdbcTemplate named, JdbcClient jdbc) {
        this.named = named;
        this.jdbc = jdbc;
    }

    /**
     * Upserts the public IPs among {@code sightings} for one data set and matches them against the
     * blocklists. Idempotent: replaying the same sightings leaves the rows unchanged.
     *
     * @return the number of distinct public IPs written
     */
    @Transactional
    public int record(String dataset, String source, Collection<Sighting> sightings) {
        if (!DATASETS.contains(dataset)) {
            throw new IllegalArgumentException("unknown data set: " + dataset);
        }
        Map<String, Sighting> perIp = new LinkedHashMap<>();
        for (Sighting sighting : sightings) {
            if (!PublicIpFilter.isPublic(sighting.ip())) {
                continue;
            }
            perIp.merge(sighting.ip(), sighting, IpEnrichmentService::merge);
        }
        if (perIp.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = perIp.values().stream()
                .map(s -> new MapSqlParameterSource()
                        .addValue("ip", s.ip())
                        .addValue("firstSeen", utc(s.firstSeen()))
                        .addValue("lastSeen", utc(s.lastSeen()))
                        .addValue("dataset", dataset)
                        .addValue("country", s.country())
                        .addValue("asn", s.asn())
                        .addValue("asnOrg", s.asnOrg())
                        .addValue("source", source))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate(UPSERT, batch);
        // IPs are validated literals without commas, so a joined list is a safe array transport.
        jdbc.sql(MATCH_TEMPLATE.formatted("x.ip = ANY(CAST(string_to_array(:ips, ',') AS inet[]))"))
                .param("ips", String.join(",", perIp.keySet()))
                .update();
        return perIp.size();
    }

    /** Re-matches every row seen within {@code window} (after a blocklist refresh, §4.4). */
    @Transactional
    public int recomputeBlocklistHits(Duration window) {
        return jdbc.sql(MATCH_TEMPLATE.formatted("x.last_seen >= now() - make_interval(secs => :seconds)"))
                .param("seconds", window.toSeconds())
                .update();
    }

    /** Earliest first/last seen; geo from the most recent sighting that has it. */
    private static Sighting merge(Sighting a, Sighting b) {
        Sighting newer = b.lastSeen().isAfter(a.lastSeen()) ? b : a;
        Sighting older = newer == a ? b : a;
        Instant first = a.firstSeen().isBefore(b.firstSeen()) ? a.firstSeen() : b.firstSeen();
        return new Sighting(a.ip(), first, newer.lastSeen(),
                newer.country() != null ? newer.country() : older.country(),
                newer.asn() != null ? newer.asn() : older.asn(),
                newer.asnOrg() != null ? newer.asnOrg() : older.asnOrg());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
