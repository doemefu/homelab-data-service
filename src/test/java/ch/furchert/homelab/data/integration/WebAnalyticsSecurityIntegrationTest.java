package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/100 §6.1: /api/webanalytics/** needs SCOPE_analytics:write AND sub in webanalytics.api.write-clients AND no
 * ROLE_* authority. Every token here is a real RS256 JWT verified against the test JWKS.
 */
class WebAnalyticsSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String EMPTY_BATCH = WebAnalyticsFixtures.batch(List.of());

    @Autowired
    MockMvc mockMvc;

    @Test
    void writeTokenWithEmptyBatch_is200WithNoStore() throws Exception {
        // Exactly one value each: a duplicated or merged header would fail stringValues.
        postWith(WebAnalyticsFixtures.writeToken())
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(header().stringValues("Pragma", "no-cache"))
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.capped").value(false));
    }

    @Test
    void missingToken_is401ProblemJsonWithNoStore() throws Exception {
        mockMvc.perform(post(WebAnalyticsFixtures.EVENTS).contentType(MediaType.APPLICATION_JSON).content(EMPTY_BATCH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void netmonReadToken_is403() throws Exception {
        assertForbidden(postWith(TestJwks.furchertChClientToken()));
    }

    @Test
    void analyticsReadToken_is403() throws Exception {
        assertForbidden(postWith(TestJwks.token(c -> c.claim("scope", List.of("analytics:read")))));
    }

    @Test
    void writeScopeForForeignClient_is403() throws Exception {
        assertForbidden(postWith(TestJwks.token(c -> c.subject("grafana").audience(List.of("grafana"))
                .claim("scope", List.of("analytics:write")))));
    }

    @Test
    void adminUserTokenWithWriteScope_is403() throws Exception {
        assertForbidden(postWith(TestJwks.token(c -> c.subject("dominic")
                .claim("role", "ADMIN")
                .claim("scope", List.of("openid", "analytics:write")))));
    }

    @Test
    void roleBearingTokenForTheAllowedSubject_is403() throws Exception {
        // A user named like the client (auth-service#143) still carries a role claim.
        assertForbidden(postWith(TestJwks.token(c -> c.claim("role", "ADMIN")
                .claim("scope", List.of("analytics:write")))));
    }

    @Test
    void getWithWriteToken_is405ProblemJsonWithNoStore() throws Exception {
        mockMvc.perform(get(WebAnalyticsFixtures.EVENTS).header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        mockMvc.perform(put(WebAnalyticsFixtures.EVENTS).header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getWithoutToken_is401() throws Exception {
        mockMvc.perform(get(WebAnalyticsFixtures.EVENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getWithNetmonToken_is403() throws Exception {
        assertForbidden(mockMvc.perform(get(WebAnalyticsFixtures.EVENTS)
                .header("Authorization", "Bearer " + TestJwks.furchertChClientToken())));
    }

    @Test
    void writeTokenOnNetmonStatus_is403() throws Exception {
        mockMvc.perform(get("/api/netmon/status").header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void otherPathsUnderThePrefix_areDenied() throws Exception {
        mockMvc.perform(get("/api/webanalytics/status").header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(post("/api/webanalytics/unknown").header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isForbidden());
        // WA-5: /visitors/{id} is a write path; another method gets 405 from MVC.
        mockMvc.perform(post("/api/webanalytics/visitors/x").header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken()))
                .andExpect(status().isMethodNotAllowed());
    }

    private ResultActions postWith(String token) throws Exception {
        return mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EMPTY_BATCH));
    }

    private static void assertForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.detail").value("The token is not allowed to access web analytics"));
    }
}
