package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/100 §7.9: the five WA-1 ingest metrics with bounded labels, exported from startup. */
class WebAnalyticsMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void wa1MetricsAreExportedWithTheirBoundedLabels() throws Exception {
        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape).contains("webanalytics_events_accepted_total ", "webanalytics_events_duplicate_total ",
                "webanalytics_enquiries_total ");
        assertThat(labels(scrape, "webanalytics_events_rejected_total"))
                .containsExactlyInAnyOrder("shape", "enum", "range", "time", "path", "other", "erased", "visitor_cap");
        assertThat(labels(scrape, "webanalytics_edge_dropped_total"))
                .containsExactlyInAnyOrder("bot", "signed_in", "consent", "invalid", "path", "rate_limited",
                        "mint_limited", "overflow", "expired");
        // WA-5 gauges: registered at startup (raw table bytes after the first measurement at ApplicationReady).
        assertThat(scrape).containsPattern("(?m)^webanalytics_ingest_capped 0\\.0$")
                .containsPattern("(?m)^webanalytics_events_today [0-9.E]+$")
                .containsPattern("(?m)^webanalytics_raw_table_bytes [1-9][0-9.E]*$");
        // Every webanalytics series carries no label other than reason.
        List<String> lines = scrape.lines().filter(l -> l.startsWith("webanalytics_")).toList();
        assertThat(lines).allSatisfy(l -> assertThat(l.replaceAll("reason=\"[a-z_]+\"", ""))
                .doesNotContain("=\""));
    }

    private static Set<String> labels(String scrape, String metric) {
        Matcher m = Pattern.compile("^" + metric + "\\{reason=\"([a-z_]+)\"}", Pattern.MULTILINE).matcher(scrape);
        return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }
}
