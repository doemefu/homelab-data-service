package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth-service's JWKS unreachable: 500 problem+json, not Boot's default error body. Standalone (not a
 * subclass of {@link AbstractIntegrationTest}) because the base class's JWKS property would win over
 * an override registered here; it still shares the base class's Postgres container.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwksUnavailableIntegrationTest {

    @DynamicPropertySource
    static void unreachableJwks(DynamicPropertyRegistry registry) {
        AbstractIntegrationTest.registerDatasource(registry);
        // Port 1 on loopback: connection refused, no DNS involved.
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/oauth2/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwks.ISSUER);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void wellFormedTokenWithUnreachableJwks_is500ProblemJson() throws Exception {
        mockMvc.perform(get("/api/netmon/status").header("Authorization", "Bearer " + TestJwks.furchertChClientToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("internal"));
    }
}
