package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** With the auth-service client secret configured, login-events exports the freshness gauge (NaN until success). */
@TestPropertySource(properties = "netmon.auth-service.client-secret=test-only")
class MetricsWithAuthClientSecretIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void loginEventsGaugeIsExported() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString(
                        "netmon_collector_last_success_timestamp_seconds{collector=\"login-events\"} NaN")));
    }
}
