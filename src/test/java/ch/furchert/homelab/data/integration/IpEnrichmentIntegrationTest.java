package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.enrichment.Sighting;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ip_enrichment upserts (docs/060 §4.3). */
class IpEnrichmentIntegrationTest extends AbstractIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-09-23T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-23T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-23T10:00:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    IpEnrichmentService enrichment;

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    @Test
    void mergesDataSetsAndKeepsMinMaxSeenAndLatestGeo() {
        enrichment.record("inbound", "cloudflare-graphql", List.of(new Sighting("203.0.113.7", T2, T3, "DE", 3320, "DTAG")));
        enrichment.record("firewall", "cloudflare-graphql", List.of(new Sighting("203.0.113.7", T1, T1, null, null, null)));
        enrichment.record("firewall", "cloudflare-graphql", List.of(new Sighting("203.0.113.7", T1, T1, null, null, null)));

        Map<String, Object> row = jdbc.sql("""
                        SELECT first_seen, last_seen, array_to_string(seen_in, ',') AS seen_in, country, asn, asn_org, blocklisted
                        FROM netmon.ip_enrichment WHERE ip = '203.0.113.7'
                        """).query().singleRow();
        assertThat(row.get("first_seen")).isEqualTo(java.sql.Timestamp.from(T1));
        assertThat(row.get("last_seen")).isEqualTo(java.sql.Timestamp.from(T3));
        assertThat(row.get("seen_in")).isEqualTo("inbound,firewall");
        assertThat(row.get("country")).isEqualTo("DE");
        assertThat(row.get("asn")).isEqualTo(3320);
        assertThat(row.get("blocklisted")).isEqualTo(false);
    }

    @Test
    void neverStoresNonPublicAddresses() {
        int written = enrichment.record("inbound", "cloudflare-graphql", List.of(
                new Sighting("10.42.0.7", T1, T1, null, null, null),
                new Sighting("192.168.1.50", T1, T1, null, null, null),
                new Sighting("fe80::1", T1, T1, null, null, null),
                new Sighting("2001:db8::7", T1, T1, null, null, null)));

        assertThat(written).isEqualTo(1);
        assertThat(jdbc.sql("SELECT host(ip) FROM netmon.ip_enrichment").query(String.class).list())
                .containsExactly("2001:db8::7");
    }

    @Test
    void duplicateSightingsInOneBatchCollapse() {
        enrichment.record("inbound", "cloudflare-graphql", List.of(
                new Sighting("203.0.113.7", T2, T2, "DE", 1, "A"),
                new Sighting("203.0.113.7", T1, T3, "CH", 2, "B")));

        Map<String, Object> row = jdbc.sql("SELECT first_seen, last_seen, country FROM netmon.ip_enrichment").query().singleRow();
        assertThat(row.get("first_seen")).isEqualTo(java.sql.Timestamp.from(T1));
        assertThat(row.get("last_seen")).isEqualTo(java.sql.Timestamp.from(T3));
        assertThat(row.get("country")).isEqualTo("CH");
    }
}
