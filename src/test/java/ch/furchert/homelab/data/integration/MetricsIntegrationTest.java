package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/060 §4.1: the collector freshness gauge is registered per collector at startup. */
class MetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void freshnessGaugeIsExportedForEveryRegisteredCollector() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"retention\"")))
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"cloudflare-requests\"")))
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"cloudflare-firewall\"")))
                .andExpect(content().string(containsString("netmon_collector_last_success_timestamp_seconds{collector=\"blocklists\"")))
                // reputation cannot run without an AbuseIPDB key, so it exports no (permanently NaN) gauge.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("collector=\"reputation\""))))
                // No IP-level label ever reaches the scrape (docs/060 §7.1).
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("ipAddress"))));
    }
}
