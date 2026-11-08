package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate N1 (docs/100 §7.5): while disabled, the rollup exports no freshness series, so {@code NetmonCollectorStale}
 * cannot fire before doemefu/homelab#350 is live. Own context with the shipped value set explicitly, so no other
 * test can register the gauge first.
 */
@TestPropertySource(properties = "netmon.collectors.webanalytics-rollup.enabled=false")
class WebAnalyticsRollupDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void disabledRollupExportsNoFreshnessSeriesAndReportsDisabled() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("collector=\"webanalytics-rollup\""))));
        String token = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));
        mockMvc.perform(get("/api/webanalytics/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectors[0].name").value("webanalytics-rollup"))
                .andExpect(jsonPath("$.collectors[0].enabled").value(false))
                .andExpect(jsonPath("$.collectors[0].stale").value(false));
    }
}
