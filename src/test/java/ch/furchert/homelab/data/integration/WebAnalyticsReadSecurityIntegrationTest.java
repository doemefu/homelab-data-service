package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/100 §8.1 (rev 3.4): read API needs SCOPE_analytics:read, sub in read-clients, no ROLE_*; GET only. */
class WebAnalyticsReadSecurityIntegrationTest extends AbstractIntegrationTest {

    static final String READ_TOKEN = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/webanalytics/status", "/api/webanalytics/overview", "/api/webanalytics/pages",
            "/api/webanalytics/pages/detail?path=/automation", "/api/webanalytics/clicks", "/api/webanalytics/sources",
            "/api/webanalytics/funnels", "/api/webanalytics/paths", "/api/webanalytics/audience",
            "/api/webanalytics/coverage"})
    void readTokenIsAcceptedWithNoStore(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Cache-Control", "no-store"))
                .andExpect(header().stringValues("Pragma", "no-cache"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/webanalytics/funnels", "/api/webanalytics/paths", "/api/webanalytics/audience",
            "/api/webanalytics/coverage"})
    void wa3bPathsRejectOtherTokensAndWriteMethods(String path) throws Exception {
        assertForbidden(mockMvc.perform(get(path).header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken())));
        assertForbidden(mockMvc.perform(get(path).header("Authorization", "Bearer "
                + TestJwks.token(c -> c.claim("role", "ADMIN").claim("scope", List.of("analytics:read"))))));
        assertForbidden(mockMvc.perform(get(path).header("Authorization", "Bearer "
                + TestJwks.token(c -> c.subject("grafana").claim("scope", List.of("analytics:read"))))));
        mockMvc.perform(post(path).header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void otherTokensAre403() throws Exception {
        assertForbidden(read(WebAnalyticsFixtures.writeToken()));
        assertForbidden(read(TestJwks.furchertChClientToken()));
        assertForbidden(read(TestJwks.token(c -> c.subject("grafana").claim("scope", List.of("analytics:read")))));
        assertForbidden(read(TestJwks.token(c -> c.subject("dominic").claim("role", "ADMIN")
                .claim("scope", List.of("openid", "analytics:read")))));
        assertForbidden(read(TestJwks.token(c -> c.claim("role", "ADMIN").claim("scope", List.of("analytics:read")))));
    }

    @Test
    void readTokenCannotWriteAndWriteMethodsOnReadPathsAre405() throws Exception {
        assertForbidden(mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                .header("Authorization", "Bearer " + READ_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(WebAnalyticsFixtures.batch(List.of()))));
        mockMvc.perform(post("/api/webanalytics/overview").header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void anonymousIs401AndUnknownPathIs403() throws Exception {
        mockMvc.perform(get("/api/webanalytics/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/webanalytics/visitors").header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isForbidden());
    }

    private ResultActions read(String token) throws Exception {
        return mockMvc.perform(get("/api/webanalytics/overview").header("Authorization", "Bearer " + token));
    }

    private static void assertForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
