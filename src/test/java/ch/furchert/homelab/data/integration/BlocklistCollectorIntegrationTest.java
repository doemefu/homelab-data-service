package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.BlocklistProperties;
import ch.furchert.homelab.data.netmon.blocklist.BlocklistCollector;
import ch.furchert.homelab.data.netmon.blocklist.BlocklistRepository;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.enrichment.IpEnrichmentService;
import ch.furchert.homelab.data.netmon.enrichment.Sighting;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** blocklists (docs/060 §4.4): applied / unchanged / failed, private-range filter, re-matching. */
class BlocklistCollectorIntegrationTest extends AbstractIntegrationTest {

    static final String SPAMHAUS_URL = "https://spamhaus.test/drop_v4.json";
    static final String FIREHOL_URL = "https://firehol.test/firehol_level1.netset";

    static final String SPAMHAUS = """
            {"cidr":"1.10.16.0/20","sblid":"SBL256894","rir":"apnic"}
            {"cidr":"198.51.100.0/24","sblid":"SBL1","rir":"arin"}
            {"type":"metadata","timestamp":1790072042,"size":2,"records":2}
            """;
    static final String FIREHOL = """
            # firehol_level1
            0.0.0.0/8
            10.0.0.0/8
            192.168.0.0/16
            1.10.16.0/20
            203.0.113.0/24
            """;

    @Autowired
    JdbcClient jdbc;
    @Autowired
    BlocklistRepository repository;
    @Autowired
    IpEnrichmentService enrichment;
    @Autowired
    CollectorRunner runner;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    private BlocklistCollector collector(Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        return new BlocklistCollector(builder.build(), repository, enrichment,
                new BlocklistProperties(SPAMHAUS_URL, FIREHOL_URL), json, runner, Clock.systemUTC());
    }

    private static void firstFetch(MockRestServiceServer s) {
        s.expect(requestTo(SPAMHAUS_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess(SPAMHAUS, MediaType.APPLICATION_JSON));
        s.expect(requestTo(FIREHOL_URL))
                .andRespond(withSuccess(FIREHOL, MediaType.TEXT_PLAIN).header(HttpHeaders.ETAG, "\"abc\""));
    }

    @Test
    void appliesBothListsDropsPrivateRangesAndMatchesKnownIps() {
        Instant now = Instant.now();
        enrichment.record("inbound", "cloudflare-graphql", List.of(
                new Sighting("1.10.20.5", now, now, null, null, null),
                new Sighting("8.8.8.8", now, now, null, null, null)));

        collector(BlocklistCollectorIntegrationTest::firstFetch).collect();

        assertThat(outcomes()).containsExactlyInAnyOrder("spamhaus-drop-v4:applied", "firehol-level1:applied");
        assertThat(jdbc.sql("SELECT list_name || ':' || count(*) FROM netmon.blocklist_entries GROUP BY list_name")
                .query(String.class).list()).containsExactlyInAnyOrder("spamhaus-drop-v4:2", "firehol-level1:2");
        // No RFC 1918 / bogon range survives the filter, so no LAN address can ever be blocklisted.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM netmon.blocklist_entries
                        WHERE cidr && '10.0.0.0/8'::cidr OR cidr && '192.168.0.0/16'::cidr OR cidr && '0.0.0.0/8'::cidr
                        """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT blocklisted FROM netmon.ip_enrichment WHERE ip = '1.10.20.5'").query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("SELECT jsonb_array_length(blocklist_hits) FROM netmon.ip_enrichment WHERE ip = '1.10.20.5'")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT blocklist_hits -> 0 ->> 'cidr' FROM netmon.ip_enrichment WHERE ip = '1.10.20.5'")
                .query(String.class).single()).isEqualTo("1.10.16.0/20");
        assertThat(jdbc.sql("SELECT blocklisted FROM netmon.ip_enrichment WHERE ip = '8.8.8.8'").query(Boolean.class).single())
                .isFalse();
    }

    @Test
    void newIpsAreMatchedOnInsert() {
        collector(BlocklistCollectorIntegrationTest::firstFetch).collect();
        Instant now = Instant.now();

        enrichment.record("firewall", "cloudflare-graphql", List.of(new Sighting("203.0.113.44", now, now, "US", 1, "X")));

        assertThat(jdbc.sql("SELECT blocklist_hits -> 0 ->> 'list' FROM netmon.ip_enrichment WHERE ip = '203.0.113.44'")
                .query(String.class).single()).isEqualTo("firehol-level1");
    }

    @Test
    void etag304AndIdenticalBodyAreUnchangedAndKeepEntries() {
        collector(BlocklistCollectorIntegrationTest::firstFetch).collect();
        List<Long> snapshotIds = entrySnapshots();

        collector(s -> {
            s.expect(requestTo(SPAMHAUS_URL)).andRespond(withSuccess(SPAMHAUS, MediaType.APPLICATION_JSON));
            s.expect(requestTo(FIREHOL_URL)).andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"abc\""))
                    .andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        }).collect();

        assertThat(outcomes()).containsExactlyInAnyOrder("spamhaus-drop-v4:applied", "firehol-level1:applied",
                "spamhaus-drop-v4:unchanged", "firehol-level1:unchanged");
        assertThat(entrySnapshots()).isEqualTo(snapshotIds);
    }

    @Test
    void failedFetchOrParseKeepsPreviousEntriesAndFailsTheRun() {
        collector(BlocklistCollectorIntegrationTest::firstFetch).collect();
        long entries = jdbc.sql("SELECT count(*) FROM netmon.blocklist_entries").query(Long.class).single();

        BlocklistCollector failing = collector(s -> {
            s.expect(requestTo(SPAMHAUS_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            s.expect(requestTo(FIREHOL_URL)).andRespond(withSuccess("1.2.3.0/24\ngarbage\n", MediaType.TEXT_PLAIN));
        });

        assertThatThrownBy(failing::collect)
                .isInstanceOfSatisfying(CollectorException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM));
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.blocklist_entries").query(Long.class).single()).isEqualTo(entries);
        assertThat(jdbc.sql("SELECT error FROM netmon.blocklist_snapshots WHERE outcome = 'failed' ORDER BY list_name")
                .query(String.class).list()).containsExactly("parse error: line 2 is not an IP or CIDR", "HTTP 500");
    }

    @Test
    void emptyListAfterFilteringIsAFailure() {
        BlocklistCollector collector = collector(s -> {
            s.expect(requestTo(SPAMHAUS_URL)).andRespond(withSuccess(SPAMHAUS, MediaType.APPLICATION_JSON));
            s.expect(requestTo(FIREHOL_URL)).andRespond(withSuccess("10.0.0.0/8\n", MediaType.TEXT_PLAIN));
        });

        assertThatThrownBy(collector::collect).isInstanceOf(CollectorException.class);
        assertThat(outcomes()).containsExactlyInAnyOrder("spamhaus-drop-v4:applied", "firehol-level1:failed");
    }

    private List<String> outcomes() {
        return jdbc.sql("SELECT list_name || ':' || outcome FROM netmon.blocklist_snapshots").query(String.class).list();
    }

    private List<Long> entrySnapshots() {
        return jdbc.sql("SELECT DISTINCT snapshot_id FROM netmon.blocklist_entries ORDER BY 1").query(Long.class).list();
    }
}
