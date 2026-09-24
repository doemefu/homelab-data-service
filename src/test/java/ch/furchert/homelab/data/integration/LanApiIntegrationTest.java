package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.NetmonTables;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /lan/connections, /lan/ufw-blocks, /lan/ssh-auth and the lan block of /ips/{ip} (docs/060 §7.1, §7.2). */
class LanApiIntegrationTest extends AbstractIntegrationTest {

    private static final String FROM = "2026-09-24T09:00:00Z";
    private static final String TO = "2026-09-24T10:00:00Z";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    private final String bearer = "Bearer " + TestJwks.furchertChClientToken();

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        jdbc.sql("""
                INSERT INTO netmon.lan_connection_snapshots (window_start, window_end, node, dport, src_ip, state,
                    peak_connections, source)
                VALUES ('2026-09-24T09:15:00Z', '2026-09-24T09:30:00Z', 'raspi5', 1883, '192.168.1.50', 'ESTABLISHED', 1, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi5', 1883, '192.168.1.50', 'ESTABLISHED', 3, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi5', 22, '10.42.0.0/16', 'TIME_WAIT', 2, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'mba1', 6443, '192.168.1.11', 'ESTABLISHED', 4, 'prometheus-textfile'),
                       ('2026-09-24T08:00:00Z', '2026-09-24T08:15:00Z', 'raspi5', 1883, '192.168.1.50', 'ESTABLISHED', 9, 'prometheus-textfile')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ufw_block_snapshots (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                VALUES ('2026-09-24T09:15:00Z', '2026-09-24T09:30:00Z', 'raspi5', '203.0.113.9', 23, 'TCP', 5, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi4', '203.0.113.9', 23, 'TCP', 7, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi5', 'other', 0, 'ICMP', 2, 'prometheus-textfile'),
                       ('2026-09-24T07:00:00Z', '2026-09-24T07:15:00Z', 'raspi5', '203.0.113.9', 23, 'TCP', 100, 'prometheus-textfile')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ssh_auth_snapshots (window_start, window_end, node, src_ip, outcome, attempts, source)
                VALUES ('2026-09-24T09:15:00Z', '2026-09-24T09:30:00Z', 'raspi5', '203.0.113.9', 'failed', 2, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi5', '203.0.113.9', 'invalid_user', 3, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'raspi5', '192.168.1.20', 'accepted', 1, 'prometheus-textfile'),
                       ('2026-09-24T09:30:00Z', '2026-09-24T09:45:00Z', 'mba2', '10.42.0.0/16', 'accepted', 4, 'prometheus-textfile')
                """).update();
        jdbc.sql("""
                INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, source)
                VALUES ('203.0.113.9', '2026-09-24T07:00:00Z', '2026-09-24T09:45:00Z', ARRAY['lan'], 'prometheus-textfile')
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
    void connectionsAggregatePerKeyWithinTheWindow() throws Exception {
        call("/api/netmon/lan/connections", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[*].node", contains("mba1", "raspi5", "raspi5")))
                .andExpect(jsonPath("$.items[1].dport").value(22))
                .andExpect(jsonPath("$.items[1].srcIp").value("10.42.0.0/16"))
                .andExpect(jsonPath("$.items[1].state").value("TIME_WAIT"))
                .andExpect(jsonPath("$.items[2].dport").value(1883))
                .andExpect(jsonPath("$.items[2].srcIp").value("192.168.1.50"))
                .andExpect(jsonPath("$.items[2].peakConnections").value(3))
                .andExpect(jsonPath("$.items[2].windows").value(2))
                .andExpect(jsonPath("$.items[2].firstSeen").value("2026-09-24T09:15:00Z"))
                .andExpect(jsonPath("$.items[2].lastSeen").value("2026-09-24T09:45:00Z"));

        call("/api/netmon/lan/connections", "from", FROM, "to", TO, "node", "raspi5", "dport", "1883")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].srcIp").value("192.168.1.50"));
    }

    @Test
    void ufwBlocksAreALowerBoundWithTotalsAndTopN() throws Exception {
        call("/api/netmon/lan/ufw-blocks", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.lowerBound").value(true))
                .andExpect(jsonPath("$.totals.blocks").value(14))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].srcIp").value("203.0.113.9"))
                .andExpect(jsonPath("$.items[0].dport").value(23))
                .andExpect(jsonPath("$.items[0].proto").value("TCP"))
                .andExpect(jsonPath("$.items[0].blocks").value(12))
                .andExpect(jsonPath("$.items[0].nodes", contains("raspi4", "raspi5")))
                .andExpect(jsonPath("$.items[1].srcIp").value("other"))
                .andExpect(jsonPath("$.items[1].dport").value(0));

        call("/api/netmon/lan/ufw-blocks", "from", FROM, "to", TO, "node", "raspi5", "limit", "1")
                .andExpect(jsonPath("$.totals.blocks").value(7))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].blocks").value(5))
                .andExpect(jsonPath("$.items[0].nodes", contains("raspi5")));
    }

    @Test
    void sshAuthSplitsOutcomesPerNodeAndSource() throws Exception {
        call("/api/netmon/lan/ssh-auth", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].node").value("mba2"))
                .andExpect(jsonPath("$.items[0].accepted").value(4))
                .andExpect(jsonPath("$.items[1].node").value("raspi5"))
                .andExpect(jsonPath("$.items[1].srcIp").value("203.0.113.9"))
                .andExpect(jsonPath("$.items[1].accepted").value(0))
                .andExpect(jsonPath("$.items[1].failed").value(2))
                .andExpect(jsonPath("$.items[1].invalidUser").value(3))
                .andExpect(jsonPath("$.items[2].srcIp").value("192.168.1.20"))
                .andExpect(jsonPath("$.items[2].accepted").value(1));

        call("/api/netmon/lan/ssh-auth", "from", FROM, "to", TO, "node", "mba2")
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void ipDetailCarriesTheLanBlock() throws Exception {
        call("/api/netmon/ips/203.0.113.9", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenIn", contains("lan")))
                .andExpect(jsonPath("$.lan.ufwBlocks").value(12))
                .andExpect(jsonPath("$.lan.sshFailed").value(5));

        // A row stored in the IPv4-mapped spelling still counts for the plain IPv4 lookup.
        jdbc.sql("""
                INSERT INTO netmon.ufw_block_snapshots (window_start, window_end, node, src_ip, dport, proto, blocks, source)
                VALUES ('2026-09-24T09:45:00Z', '2026-09-24T10:00:00Z', 'mba1', '::ffff:203.0.113.9', 23, 'TCP', 1, 'prometheus-textfile')
                """).update();
        call("/api/netmon/ips/203.0.113.9", "from", FROM, "to", TO)
                .andExpect(jsonPath("$.lan.ufwBlocks").value(13));
    }

    @Test
    void emptyWindowsReturnEmptyShapes() throws Exception {
        call("/api/netmon/lan/ufw-blocks", "from", "2026-09-01T00:00:00Z", "to", "2026-09-02T00:00:00Z")
                .andExpect(jsonPath("$.lowerBound").value(true))
                .andExpect(jsonPath("$.totals.blocks").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));
        call("/api/netmon/lan/connections", "from", "2026-09-01T00:00:00Z", "to", "2026-09-02T00:00:00Z")
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void invalidParametersAreProblemJson() throws Exception {
        for (String uri : List.of("/api/netmon/lan/connections", "/api/netmon/lan/ufw-blocks", "/api/netmon/lan/ssh-auth")) {
            call(uri, "from", TO, "to", FROM)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("invalid_window"));
            call(uri, "from", "2026-08-01T00:00:00Z", "to", TO)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_window"));
        }
        call("/api/netmon/lan/connections", "dport", "70000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/lan/connections", "dport", "ssh")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("/api/netmon/lan/ufw-blocks", "limit", "51")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
    }

    @Test
    void lanEndpointsRequireTheNetmonToken() throws Exception {
        for (String uri : List.of("/api/netmon/lan/connections", "/api/netmon/lan/ufw-blocks", "/api/netmon/lan/ssh-auth")) {
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
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
    }
}
