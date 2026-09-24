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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /logins/summary, /logins/events and the logins block of /ips/{ip} (docs/060 §7.1, §7.2, NM-4). furchert-ch#64
 * has not been built yet, so the spec's JSON is the contract: 4 summary members, 7 byIp fields, 3 bySubject fields,
 * 4 timeline fields and 9 event fields, absent values as null, never omitted.
 */
class LoginApiIntegrationTest extends AbstractIntegrationTest {

    private static final String SUMMARY = "/api/netmon/logins/summary";
    private static final String EVENTS = "/api/netmon/logins/events";
    private static final String FROM = "2026-09-24T08:00:00Z";
    private static final String TO = "2026-09-24T10:00:00Z";
    /** Synthetic HMACs; B is the one seen on dominic's success, F on bob's. */
    private static final String HMAC_B = "b".repeat(64);
    private static final String HMAC_C = "c".repeat(64);
    private static final String HMAC_D = "d".repeat(64);
    private static final String HMAC_E = "e".repeat(64);
    private static final String HMAC_F = "3fa9c1d2" + "f".repeat(56);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    private final String bearer = "Bearer " + TestJwks.furchertChClientToken();

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        row(1, "2026-09-24T08:10:00Z", "failure", "203.0.113.7", "cf-connecting-ip", HMAC_B, null, "Mozilla/5.0");
        row(2, "2026-09-24T08:20:00Z", "failure", "203.0.113.7", "cf-connecting-ip", HMAC_C, null, null);
        row(3, "2026-09-24T08:30:00Z", "locked", "203.0.113.7", "cf-connecting-ip", HMAC_B, null, null);
        row(4, "2026-09-24T09:05:00Z", "success", "198.51.100.9", "cf-connecting-ip", HMAC_B, "dominic", null);
        row(5, "2026-09-24T09:10:00Z", "failure", null, "remote-addr", HMAC_D, null, null);
        row(6, "2026-09-24T09:15:00Z", "success", "10.42.1.5", "remote-addr", HMAC_E, "alice", null);
        // bob's only success is outside the window; the failure against his HMAC is inside.
        row(7, "2026-09-20T12:00:00Z", "success", "198.51.100.9", "cf-connecting-ip", HMAC_F, "bob", null);
        row(8, "2026-09-24T09:20:00Z", "failure", "203.0.113.8", "cf-connecting-ip", HMAC_F, null, null);
        // Outside the window.
        row(9, "2026-09-24T07:00:00Z", "failure", "203.0.113.7", "cf-connecting-ip", HMAC_C, null, null);
        jdbc.sql("""
                        INSERT INTO netmon.ip_enrichment (ip, first_seen, last_seen, seen_in, country, blocklisted,
                            blocklist_hits, abuseipdb_score, abuseipdb_reports, abuseipdb_checked_at, source)
                        VALUES ('203.0.113.7', '2026-09-24T07:00:00Z', '2026-09-24T08:30:00Z', '{login}', 'DE', true,
                                '[{"list":"firehol-level1","cidr":"203.0.113.0/24","fetchedAt":"2026-09-24T05:00:00Z"}]',
                                87, 412, '2026-09-24T09:00:00Z', 'auth-service')
                        """).update();
    }

    private void row(int n, String at, String outcome, String ip, String ipSource, String hmac, String subject,
                     String userAgent) {
        jdbc.sql("""
                        INSERT INTO netmon.login_events (event_id, occurred_at, outcome, client_ip, ip_source,
                            username_hmac, subject, user_agent, source_service, source)
                        VALUES (CAST(:id AS uuid), CAST(:at AS timestamptz), :outcome, CAST(:ip AS inet), :ipSource,
                                :hmac, :subject, :ua, 'auth-service', 'auth-service')
                        """)
                .param("id", "0b6f1e2a-1111-4c1d-9a0e-%012d".formatted(n)).param("at", at).param("outcome", outcome)
                .param("ip", ip).param("ipSource", ipSource).param("hmac", hmac).param("subject", subject)
                .param("ua", userAgent)
                .update();
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
        call(SUMMARY, "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.totals", aMapWithSize(3)))
                .andExpect(jsonPath("$.totals.success").value(2))
                .andExpect(jsonPath("$.totals.failure").value(4))
                .andExpect(jsonPath("$.totals.locked").value(1))
                // Most unsuccessful attempts first; events without an IP are not listed.
                .andExpect(jsonPath("$.byIp[*].ip", contains("203.0.113.7", "203.0.113.8", "10.42.1.5", "198.51.100.9")))
                .andExpect(jsonPath("$.byIp[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$.byIp[0].success").value(0))
                .andExpect(jsonPath("$.byIp[0].failure").value(2))
                .andExpect(jsonPath("$.byIp[0].locked").value(1))
                .andExpect(jsonPath("$.byIp[0].country").value("DE"))
                .andExpect(jsonPath("$.byIp[0].blocklisted").value(true))
                .andExpect(jsonPath("$.byIp[0].abuseScore").value(87))
                .andExpect(jsonPath("$.byIp[2].country").value(nullValue()))
                .andExpect(jsonPath("$.byIp[2].blocklisted").value(false))
                .andExpect(jsonPath("$.byIp[2].abuseScore").value(nullValue()))
                .andExpect(jsonPath("$.bySubject[*].subject", contains("dominic", "bob", "alice")))
                .andExpect(jsonPath("$.bySubject[0]", aMapWithSize(3)))
                .andExpect(jsonPath("$.bySubject[0].success").value(1))
                // The locked attempt with dominic's HMAC is not a failure.
                .andExpect(jsonPath("$.bySubject[0].failureSameHmac").value(1))
                .andExpect(jsonPath("$.bySubject[1].success").value(0))
                .andExpect(jsonPath("$.bySubject[1].failureSameHmac").value(1))
                .andExpect(jsonPath("$.bySubject[2].failureSameHmac").value(0))
                .andExpect(jsonPath("$.timeline", hasSize(2)))
                .andExpect(jsonPath("$.timeline[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.timeline[0].bucketStart").value("2026-09-24T08:00:00Z"))
                .andExpect(jsonPath("$.timeline[0].success").value(0))
                .andExpect(jsonPath("$.timeline[0].failure").value(2))
                .andExpect(jsonPath("$.timeline[0].locked").value(1))
                .andExpect(jsonPath("$.timeline[1].success").value(2))
                .andExpect(jsonPath("$.timeline[1].failure").value(2));
    }

    @Test
    void summaryLimitAndDailyTimeline() throws Exception {
        call(SUMMARY, "from", "2026-09-10T00:00:00Z", "to", TO, "limit", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byIp", hasSize(1)))
                .andExpect(jsonPath("$.bySubject", hasSize(1)))
                .andExpect(jsonPath("$.timeline[*].bucketStart", contains("2026-09-20T00:00:00Z", "2026-09-24T00:00:00Z")))
                .andExpect(jsonPath("$.totals.success").value(3));
    }

    @Test
    void repeatedSuccessesDoNotMultiplyFailureSameHmac() throws Exception {
        // A second success of dominic with the same HMAC (outside the window) must not double the one failure.
        row(10, "2026-09-21T12:00:00Z", "success", "198.51.100.9", "cf-connecting-ip", HMAC_B, "dominic", null);
        call(SUMMARY, "from", FROM, "to", TO)
                .andExpect(jsonPath("$.bySubject[0].subject").value("dominic"))
                .andExpect(jsonPath("$.bySubject[0].failureSameHmac").value(1));
    }

    @Test
    void emptyWindowGivesZerosAndEmptyLists() throws Exception {
        call(SUMMARY, "from", "2026-09-01T00:00:00Z", "to", "2026-09-02T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.failure").value(0))
                .andExpect(jsonPath("$.byIp", hasSize(0)))
                .andExpect(jsonPath("$.bySubject", hasSize(0)))
                .andExpect(jsonPath("$.timeline", hasSize(0)));
    }

    @Test
    void eventsHaveTheContractShapeNewestFirst() throws Exception {
        call(EVENTS, "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.items", hasSize(7)))
                .andExpect(jsonPath("$.items[*].occurredAt", contains("2026-09-24T09:20:00Z", "2026-09-24T09:15:00Z",
                        "2026-09-24T09:10:00Z", "2026-09-24T09:05:00Z", "2026-09-24T08:30:00Z", "2026-09-24T08:20:00Z",
                        "2026-09-24T08:10:00Z")))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(9)))
                .andExpect(jsonPath("$.items[0].outcome").value("failure"))
                .andExpect(jsonPath("$.items[0].clientIp").value("203.0.113.8"))
                .andExpect(jsonPath("$.items[0].ipSource").value("cf-connecting-ip"))
                .andExpect(jsonPath("$.items[0].subject").value(nullValue()))
                .andExpect(jsonPath("$.items[0].usernameHmacPrefix").value("3fa9c1d2"))
                .andExpect(jsonPath("$.items[0].userAgent").value(nullValue()))
                .andExpect(jsonPath("$.items[0].country").value(nullValue()))
                .andExpect(jsonPath("$.items[0].blocklisted").value(false))
                .andExpect(jsonPath("$.items[1].subject").value("alice"))
                .andExpect(jsonPath("$.items[2].clientIp").value(nullValue()))
                .andExpect(jsonPath("$.items[2].ipSource").value("remote-addr"))
                .andExpect(jsonPath("$.items[6].userAgent").value("Mozilla/5.0"))
                .andExpect(jsonPath("$.items[6].country").value("DE"))
                .andExpect(jsonPath("$.items[6].blocklisted").value(true))
                // Only the 8-character prefix is ever served (docs/060 §7.2, §10).
                .andExpect(content().string(not(containsString(HMAC_B))));
    }

    @Test
    void eventsPageWithAnOpaqueCursor() throws Exception {
        MvcResult first = call(EVENTS, "from", FROM, "to", TO, "limit", "4")
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
        call(EVENTS, "from", FROM, "to", TO, "limit", "4", "cursor", cursor)
                .andExpect(jsonPath("$.items[*].occurredAt", contains("2026-09-24T08:30:00Z", "2026-09-24T08:20:00Z",
                        "2026-09-24T08:10:00Z")))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        assertThat(cursor).doesNotContain("2026");
    }

    @Test
    void eventsFilterByOutcomeAndIp() throws Exception {
        call(EVENTS, "from", FROM, "to", TO, "outcome", "success")
                .andExpect(jsonPath("$.items[*].subject", contains("alice", "dominic")));
        call(EVENTS, "from", FROM, "to", TO, "ip", "203.0.113.7")
                .andExpect(jsonPath("$.items", hasSize(3)));
        call(EVENTS, "from", FROM, "to", TO, "ip", "203.0.113.7", "outcome", "locked")
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void invalidParametersAre400() throws Exception {
        call(EVENTS, "outcome", "denied")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.instance").value(EVENTS));
        call(EVENTS, "ip", "not-an-ip").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_parameter"));
        call(EVENTS, "limit", "501").andExpect(status().isBadRequest());
        call(EVENTS, "cursor", "%%%").andExpect(status().isBadRequest());
        call(SUMMARY, "limit", "51").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_parameter"));
        call(SUMMARY, "from", TO, "to", FROM).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_window"));
    }

    @Test
    void ipDetailCountsLogins() throws Exception {
        call("/api/netmon/ips/203.0.113.7", "from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seenIn", contains("login")))
                .andExpect(jsonPath("$.logins", aMapWithSize(3)))
                .andExpect(jsonPath("$.logins.success").value(0))
                .andExpect(jsonPath("$.logins.failure").value(2))
                .andExpect(jsonPath("$.logins.locked").value(1));
    }

    @Test
    void withoutTokenIs401AndWithAForeignSubjectIs403() throws Exception {
        for (String uri : new String[] {SUMMARY, EVENTS}) {
            mockMvc.perform(get(uri))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("unauthorized"));
            mockMvc.perform(get(uri).header("Authorization", "Bearer " + TestJwks.token(c -> c.subject("grafana"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
    }
}
