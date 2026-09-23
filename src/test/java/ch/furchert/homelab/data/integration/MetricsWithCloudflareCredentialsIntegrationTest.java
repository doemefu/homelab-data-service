package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** With Cloudflare credentials configured, both Cloudflare collectors export the freshness gauge (NaN until success). */
@TestPropertySource(properties = {"netmon.cloudflare.api-token=test-only", "netmon.cloudflare.zone-id=test-zone"})
class MetricsWithCloudflareCredentialsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void cloudflareGaugesAreExported() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"cloudflare-requests\"} NaN")))
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"cloudflare-firewall\"} NaN")));
    }
}
