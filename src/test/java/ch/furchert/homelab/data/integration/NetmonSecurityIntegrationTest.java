package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/060 §7.5 (v1): /api/netmon/** needs SCOPE_netmon:read AND sub in netmon.api.allowed-clients.
 * Every token here is a real RS256 JWT verified against the test JWKS.
 */
class NetmonSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String STATUS = "/api/netmon/status";

    @Autowired
    MockMvc mockMvc;

    @Test
    void furchertChClientCredentialsToken_isAccepted() throws Exception {
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + TestJwks.furchertChClientToken()))
                .andExpect(status().isOk());
    }

    @Test
    void scopeAsSpaceDelimitedString_isAccepted() throws Exception {
        String token = TestJwks.token(c -> c.claim("scope", "netmon:read"));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void missingToken_is401ProblemJson() throws Exception {
        mockMvc.perform(get(STATUS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.instance").value(STATUS));
    }

    @Test
    void adminUserTokenWithoutNetmonScope_is403() throws Exception {
        // A user token issued to any client carries role=ADMIN; v1 must not accept it.
        String token = TestJwks.token(c -> c
                .subject("dominic")
                .claim("role", "ADMIN")
                .claim("scope", List.of("openid", "profile", "email")));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void adminRoleForAllowedSubjectWithoutScope_is403() throws Exception {
        String token = TestJwks.token(c -> c.claim("role", "ADMIN").claim("scope", List.of("openid")));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void netmonScopeForForeignClient_is403() throws Exception {
        String token = TestJwks.token(c -> c.subject("grafana").audience(List.of("grafana")));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void wrongIssuer_is401() throws Exception {
        String token = TestJwks.token(c -> c.issuer("https://evil.example"));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void expiredToken_is401() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String token = TestJwks.token(c -> c.issuedAt(past.minusSeconds(900)).expiresAt(past));
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithUnpublishedKey_is401() throws Exception {
        String token = TestJwks.tokenSignedWithForeignKey(c -> {
        });
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedToken_is401WithoutEchoingIt() throws Exception {
        mockMvc.perform(get(STATUS).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("not-a-jwt"))));
    }

    @Test
    void healthInfoAndPrometheus_arePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpoints_areDenied() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer " + TestJwks.furchertChClientToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void pathsOutsideTheApi_areDenied() throws Exception {
        mockMvc.perform(get("/api/other").header("Authorization", "Bearer " + TestJwks.furchertChClientToken()))
                .andExpect(status().isForbidden());
    }
}
