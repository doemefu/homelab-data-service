package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ID tokens are never accepted as bearer tokens (auth-service INTERFACES.md, "Reject ID tokens"). They are signed
 * with the same key as access tokens; auth-service adds {@code azp} to every ID token and to no access token, so the
 * decoder rejects any token that carries it, before authorization (401, not 403). Real RS256 JWTs against the test
 * JWKS.
 */
class IdTokenRejectionIntegrationTest extends AbstractIntegrationTest {

    private static final String NETMON_STATUS = "/api/netmon/status";
    private static final String WEBANALYTICS_STATUS = "/api/webanalytics/status";

    @Autowired
    MockMvc mockMvc;

    /** Shaped like an auth-service ID token for a furchert-ch user sign-in: no scope, role, azp, sid, auth_time. */
    private static String idToken() {
        Instant now = Instant.now();
        return TestJwks.token(c -> c
                .subject("dominic")
                .audience(List.of("furchert-ch"))
                .claims(claims -> claims.remove("scope"))
                .claim("azp", "furchert-ch")
                .claim("role", "ADMIN")
                .claim("sid", "session-id")
                .claim("auth_time", now.getEpochSecond()));
    }

    @Test
    void idToken_onNetmon_is401() throws Exception {
        mockMvc.perform(get(NETMON_STATUS).header("Authorization", "Bearer " + idToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void idToken_onWebAnalytics_is401() throws Exception {
        mockMvc.perform(get(WEBANALYTICS_STATUS).header("Authorization", "Bearer " + idToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void otherwiseAcceptedNetmonToken_withAzp_is401() throws Exception {
        // Without azp this exact token gets 200 (NetmonSecurityIntegrationTest), so azp alone decides.
        String token = TestJwks.token(c -> c.claim("azp", "furchert-ch"));
        mockMvc.perform(get(NETMON_STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherwiseAcceptedAnalyticsReadToken_withAzp_is401() throws Exception {
        String token = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")).claim("azp", "furchert-ch"));
        mockMvc.perform(get(WEBANALYTICS_STATUS).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenWithoutAzp_isStillAccepted() throws Exception {
        mockMvc.perform(get(NETMON_STATUS).header("Authorization", "Bearer " + TestJwks.furchertChClientToken()))
                .andExpect(status().isOk());
    }
}
