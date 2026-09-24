package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.NetmonTables;
import ch.furchert.homelab.data.support.TestJwks;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /inbound/summary, /inbound/firewall-events and /ips/{ip} (docs/060 §7.1, §7.2, §7.4). */
class InboundApiIntegrationTest extends AbstractIntegrationTest {

    private static final String FROM = "2026-09-23T00:00:00Z";
    private static final String TO = "2026-09-24T00:00:00Z";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    private final String bearer = "Bearer " + TestJwks.furchertChClientToken();

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        jdbc.sql("""
                INSERT INTO netmon.inbound_request_groups (window_start, window_end, is_final, client_ip, country,
                    host, method, path, status, request_count, sample_interval, source)
                VALUES ('2026-09-23T08:00:00Z', '2026-09-23T09:00:00Z', true, '203.0.113.7', 'DE',
                        'furchert.ch', 'GET', '/de', 200, 120, 1, 'cloudflare-graphql'),
                       ('2026-09-23T08:00:00Z', '2026-09-23T09:00:00Z', true, '203.0.113.7', 'DE',
                        'furchert.ch', 'GET', '/wp-login.php', 404, 5, 1, 'cloudflare-graphql'),
                       ('2026-09-23T08:00:00Z', '2026-09-23T09:00:00Z', true, '198.51.100.9', 'US',
                        'auth.furchert.ch', 'POST', '/login', 401, 7, 10, 'cloudflare-graphql'),
                       ('2026-09-20T08:00:00Z', '2026-09-20T09:00:00Z', true, '203.0.113.7', 'DE',
                        'furchert.ch', 'GET', '/de', 200, 1, 1, 'cloudflare-graphql')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.firewall_events (occurred_at, ray_name, client_ip, country, asn, asn_org, action,
                    security_source, rule_id, host, method, path, user_agent, source)
                VALUES ('2026-09-23T08:10:00Z', 'ray-a', '198.51.100.9', 'US', 14061, 'DIGITALOCEAN', 'block',
                        'firewallManaged', 'rule-1', 'auth.furchert.ch', 'POST', '/login', 'curl/8.0', 'cloudflare-graphql'),
                       ('2026-09-23T08:20:00Z', 'ray-b', '198.51.100.9', 'US', 14061, 'DIGITALOCEAN', 'managed_challenge',
                        'botFight', NULL, 'auth.furchert.ch', 'POST', '/login', NULL, 'cloudflare-graphql'),
                       ('2026-09-23T08:30:00Z', 'ray-c', '198.51.100.9', 'US', 14061, 'DIGITALOCEAN', 'block',
                        'firewallManaged', 'rule-1', 'auth.furchert.ch', 'POST', '/login', 'curl/8.0', 'cloudflare-graphql')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, country, asn, asn_org, blocklist_hits,
                    blocklisted, abuseipdb_score, abuseipdb_reports, abuseipdb_checked_at, source)
                VALUES ('203.0.113.7', '2026-09-20T08:00:00Z', '2026-09-23T09:00:00Z', ARRAY['inbound'], 'DE', 3320, 'DTAG',
                        '[]', false, 12, 4, '2026-09-23T09:30:00Z', 'cloudflare-graphql'),
                       ('198.51.100.9', '2026-09-23T08:00:00Z', '2026-09-23T09:00:00Z', ARRAY['inbound', 'firewall'], 'US',
                        14061, 'DIGITALOCEAN',
                        '[{"list":"firehol-level1","cidr":"198.51.100.0/24","fetchedAt":"2026-09-23T05:00:00Z"}]', true,
                        NULL, NULL, NULL, 'cloudflare-graphql')
                """).update();
    }

    private ResultActions call(String uri, String... params) throws Exception {
        var request = get(uri).header("Authorization", bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    @Test
    void summaryHasTheContractShape() throws Exception {
        call("/api/netmon/inbound/summary", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.window.from").value(FROM))
                .andExpect(jsonPath("$.window.to").value(TO))
                .andExpect(jsonPath("$.totals.requests").value(132))
                .andExpect(jsonPath("$.totals.uniqueClientIps").value(2))
                .andExpect(jsonPath("$.totals.sampled").value(true))
                .andExpect(jsonPath("$.topClientIps", hasSize(2)))
                .andExpect(jsonPath("$.topClientIps[0].ip").value("203.0.113.7"))
                .andExpect(jsonPath("$.topClientIps[0].requests").value(125))
                .andExpect(jsonPath("$.topClientIps[0].country").value("DE"))
                .andExpect(jsonPath("$.topClientIps[0].asn").value(3320))
                .andExpect(jsonPath("$.topClientIps[0].asnOrg").value("DTAG"))
                .andExpect(jsonPath("$.topClientIps[0].blocklisted").value(false))
                .andExpect(jsonPath("$.topClientIps[0].abuseScore").value(12))
                .andExpect(jsonPath("$.topClientIps[0].firewallEvents").value(0))
                .andExpect(jsonPath("$.topClientIps[1].ip").value("198.51.100.9"))
                .andExpect(jsonPath("$.topClientIps[1].blocklisted").value(true))
                .andExpect(jsonPath("$.topClientIps[1].abuseScore").value(nullValue()))
                .andExpect(jsonPath("$.topClientIps[1].firewallEvents").value(3))
                .andExpect(jsonPath("$.topCountries[0].country").value("DE"))
                .andExpect(jsonPath("$.topCountries[0].requests").value(125))
                .andExpect(jsonPath("$.topAsns[1].asn").value(14061))
                .andExpect(jsonPath("$.topAsns[1].asnOrg").value("DIGITALOCEAN"))
                .andExpect(jsonPath("$.topHosts[0].host").value("furchert.ch"))
                .andExpect(jsonPath("$.topPaths[0].path").value("/de"))
                .andExpect(jsonPath("$.topPaths[0].host").value("furchert.ch"))
                .andExpect(jsonPath("$.statuses[*].status", contains(200, 401, 404)))
                .andExpect(jsonPath("$.timeline", hasSize(1)))
                .andExpect(jsonPath("$.timeline[0].bucketStart").value("2026-09-23T08:00:00Z"))
                .andExpect(jsonPath("$.timeline[0].requests").value(132))
                .andExpect(content().string(containsString("\"abuseScore\":null")));
    }

    @Test
    void summaryFiltersByHostAndLimitsTopLists() throws Exception {
        call("/api/netmon/inbound/summary", "from", FROM, "to", TO, "host", "auth.furchert.ch", "limit", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.requests").value(7))
                .andExpect(jsonPath("$.totals.sampled").value(true))
                .andExpect(jsonPath("$.topClientIps", hasSize(1)));
    }

    @Test
    void topClientIpFirewallCountFollowsTheHostFilter() throws Exception {
        jdbc.sql("""
                INSERT INTO netmon.firewall_events (occurred_at, ray_name, client_ip, action, security_source, host, source)
                VALUES ('2026-09-23T08:40:00Z', 'ray-d', '203.0.113.7', 'block', 'firewallManaged', 'auth.furchert.ch',
                        'cloudflare-graphql')
                """).update();

        call("/api/netmon/inbound/summary", "from", FROM, "to", TO)
                .andExpect(jsonPath("$.topClientIps[0].ip").value("203.0.113.7"))
                .andExpect(jsonPath("$.topClientIps[0].firewallEvents").value(1));
        call("/api/netmon/inbound/summary", "from", FROM, "to", TO, "host", "furchert.ch")
                .andExpect(jsonPath("$.topClientIps[0].ip").value("203.0.113.7"))
                .andExpect(jsonPath("$.topClientIps[0].firewallEvents").value(0));
    }

    @Test
    void longWindowsUseDailyTimelineBuckets() throws Exception {
        call("/api/netmon/inbound/summary", "from", "2026-09-15T00:00:00Z", "to", TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline[*].bucketStart", contains("2026-09-20T00:00:00Z", "2026-09-23T00:00:00Z")))
                .andExpect(jsonPath("$.totals.requests").value(133));
    }

    @Test
    void emptyWindowReturnsZerosAndEmptyLists() throws Exception {
        call("/api/netmon/inbound/summary", "from", "2026-01-01T00:00:00Z", "to", "2026-01-02T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.requests").value(0))
                .andExpect(jsonPath("$.totals.uniqueClientIps").value(0))
                .andExpect(jsonPath("$.totals.sampled").value(false))
                .andExpect(jsonPath("$.topClientIps", hasSize(0)))
                .andExpect(jsonPath("$.timeline", hasSize(0)));
    }

    @Test
    void invalidWindowAndLimitAreProblemJson() throws Exception {
        call("/api/netmon/inbound/summary", "from", "2026-08-01T00:00:00Z", "to", TO)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("invalid_window"))
                .andExpect(jsonPath("$.instance", endsWith("/api/netmon/inbound/summary")));
        call("/api/netmon/inbound/summary", "to", "not-a-time")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_window"));
        call("/api/netmon/inbound/summary", "limit", "51")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/inbound/firewall-events", "limit", "501")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/inbound/firewall-events", "ip", "evil.example")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/inbound/firewall-events", "cursor", "garbage!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
    }

    @Test
    void firewallEventsPageNewestFirstWithAnOpaqueCursor() throws Exception {
        String firstPage = call("/api/netmon/inbound/firewall-events", "from", FROM, "to", TO, "limit", "2")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-09-23T08:30:00Z"))
                .andExpect(jsonPath("$.items[0].rayName").value("ray-c"))
                .andExpect(jsonPath("$.items[0].clientIp").value("198.51.100.9"))
                .andExpect(jsonPath("$.items[0].country").value("US"))
                .andExpect(jsonPath("$.items[0].asn").value(14061))
                .andExpect(jsonPath("$.items[0].asnOrg").value("DIGITALOCEAN"))
                .andExpect(jsonPath("$.items[0].action").value("block"))
                .andExpect(jsonPath("$.items[0].securitySource").value("firewallManaged"))
                .andExpect(jsonPath("$.items[0].ruleId").value("rule-1"))
                .andExpect(jsonPath("$.items[0].host").value("auth.furchert.ch"))
                .andExpect(jsonPath("$.items[0].method").value("POST"))
                .andExpect(jsonPath("$.items[0].path").value("/login"))
                .andExpect(jsonPath("$.items[0].userAgent").value("curl/8.0"))
                .andExpect(jsonPath("$.items[0].blocklisted").value(true))
                .andExpect(jsonPath("$.items[1].ruleId").value(nullValue()))
                .andExpect(content().string(containsString("\"userAgent\":null")))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstPage, "$.nextCursor");

        call("/api/netmon/inbound/firewall-events", "from", FROM, "to", TO, "limit", "2", "cursor", cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].rayName").value("ray-a"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(content().string(containsString("\"nextCursor\":null")));
    }

    @Test
    void firewallEventsFilterByActionHostAndIp() throws Exception {
        call("/api/netmon/inbound/firewall-events", "from", FROM, "to", TO, "action", "managed_challenge")
                .andExpect(jsonPath("$.items", hasSize(1)));
        call("/api/netmon/inbound/firewall-events", "from", FROM, "to", TO, "host", "furchert.ch")
                .andExpect(jsonPath("$.items", hasSize(0)));
        call("/api/netmon/inbound/firewall-events", "from", FROM, "to", TO, "ip", "198.51.100.9")
                .andExpect(jsonPath("$.items", hasSize(3)));
    }

    @Test
    void ipDetailHasTheContractShape() throws Exception {
        call("/api/netmon/ips/198.51.100.9", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ip").value("198.51.100.9"))
                .andExpect(jsonPath("$.firstSeen").value("2026-09-23T08:00:00Z"))
                .andExpect(jsonPath("$.lastSeen").value("2026-09-23T09:00:00Z"))
                .andExpect(jsonPath("$.seenIn", contains("firewall", "inbound")))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.asn").value(14061))
                .andExpect(jsonPath("$.asnOrg").value("DIGITALOCEAN"))
                .andExpect(jsonPath("$.blocklists[0].list").value("firehol-level1"))
                .andExpect(jsonPath("$.blocklists[0].cidr").value("198.51.100.0/24"))
                .andExpect(jsonPath("$.blocklists[0].fetchedAt").value("2026-09-23T05:00:00Z"))
                .andExpect(jsonPath("$.abuseIpDb").value(nullValue()))
                .andExpect(jsonPath("$.inbound.requests").value(7))
                .andExpect(jsonPath("$.inbound.topHosts[0].host").value("auth.furchert.ch"))
                .andExpect(jsonPath("$.inbound.topPaths[0].path").value("/login"))
                .andExpect(jsonPath("$.inbound.statuses[0].status").value(401))
                .andExpect(jsonPath("$.firewallEvents", hasSize(3)))
                .andExpect(jsonPath("$.firewallEvents[0].rayName").value("ray-c"))
                .andExpect(content().string(containsString("\"logins\":null")))
                // NM-3: the lan block is always present; this IP has no LAN rows.
                .andExpect(jsonPath("$.lan.ufwBlocks").value(0))
                .andExpect(jsonPath("$.lan.sshFailed").value(0));

        call("/api/netmon/ips/203.0.113.7", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abuseIpDb.score").value(12))
                .andExpect(jsonPath("$.abuseIpDb.reports").value(4))
                .andExpect(jsonPath("$.abuseIpDb.checkedAt").value("2026-09-23T09:30:00Z"))
                .andExpect(jsonPath("$.blocklists", hasSize(0)))
                .andExpect(jsonPath("$.firewallEvents", hasSize(0)));
    }

    @Test
    void ipDetailErrors() throws Exception {
        call("/api/netmon/ips/8.8.8.8")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_found"));
        call("/api/netmon/ips/not-an-ip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/ips/203.0.113.7", "from", "2026-09-23T10:00:00Z", "to", "2026-09-23T09:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_window"));
    }

    @Test
    void newEndpointsRequireTheNetmonToken() throws Exception {
        for (String uri : List.of("/api/netmon/inbound/summary", "/api/netmon/inbound/firewall-events",
                "/api/netmon/ips/203.0.113.7")) {
            mockMvc.perform(get(uri))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("unauthorized"));
            mockMvc.perform(get(uri).header("Authorization",
                            "Bearer " + TestJwks.token(c -> c.claim("scope", List.of("openid")))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
            mockMvc.perform(get(uri).header("Authorization",
                            "Bearer " + TestJwks.token(c -> c.subject("dominic").claim("role", "ADMIN"))))
                    .andExpect(status().isForbidden());
        }
    }
}
