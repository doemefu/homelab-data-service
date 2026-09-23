package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/netmon/status (docs/060 §7.1, §7.2, §7.4). */
class StatusEndpointIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + TestJwks.furchertChClientToken();
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = 'retention'").update();
    }

    @Test
    void neverRunCollector_isListedWithExplicitNulls() throws Exception {
        mockMvc.perform(get("/api/netmon/status").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.collectors[0].name").value("retention"))
                .andExpect(jsonPath("$.collectors[0].enabled").value(true))
                .andExpect(jsonPath("$.collectors[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.collectors[0].stale").value(false))
                // Absent optional values are null, never omitted (§7.1).
                .andExpect(content().string(containsString("\"lastSuccessAt\":null")))
                .andExpect(content().string(containsString("\"lastWindowEnd\":null")))
                .andExpect(content().string(containsString("\"lastErrorCode\":null")));
    }

    @Test
    void failingCollector_exposesCodeButNeverTheMessage() throws Exception {
        OffsetDateTime success = OffsetDateTime.of(2026, 9, 1, 3, 30, 0, 0, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO netmon.collector_state
                          (collector, last_success_at, consecutive_failures, last_error, last_error_code)
                        VALUES ('retention', ?, 2, 'CollectorException: upstream said no', 'upstream')
                        """)
                .param(success)
                .update();

        mockMvc.perform(get("/api/netmon/status").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectors[0].lastSuccessAt").value("2026-09-01T03:30:00Z"))
                .andExpect(jsonPath("$.collectors[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.collectors[0].lastErrorCode").value("upstream"))
                .andExpect(jsonPath("$.collectors[0].stale").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("upstream said no"))));
    }

    @Test
    void unknownApiPath_is404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/netmon/does-not-exist").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.instance", endsWith("/api/netmon/does-not-exist")));
    }

    @Test
    void writeMethods_areRejectedAsProblemJson() throws Exception {
        mockMvc.perform(post("/api/netmon/status").header("Authorization", bearer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
    }

    @Test
    void unknownQueryParameters_areIgnored() throws Exception {
        mockMvc.perform(get("/api/netmon/status").param("foo", "bar").header("Authorization", bearer))
                .andExpect(status().isOk());
    }
}
