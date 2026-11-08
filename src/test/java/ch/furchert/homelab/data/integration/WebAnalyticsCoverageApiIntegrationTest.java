package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /coverage} with a configured go-live date (docs/100 §8.2, rev 3.5): comparisons only from
 * {@code baselineFrom}; pre-launch Cloudflare rows stay stored but are not compared; enquiries by submission day.
 */
@TestPropertySource(properties = "webanalytics.coverage.go-live-date=2026-11-05")
class WebAnalyticsCoverageApiIntegrationTest extends AbstractIntegrationTest {

    static final String TOKEN = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
    }

    @Test
    void coverageStartsAtTheGoLiveDate() throws Exception {
        AggregateRows rows = new AggregateRows(jdbc);
        for (LocalDate d = LocalDate.parse("2026-11-01"); !d.isAfter(LocalDate.parse("2026-11-07")); d = d.plusDays(1)) {
            rows.rollupDay(d, true);
            rows.cfBaseline(d, "/", "de", 100, d.equals(LocalDate.parse("2026-11-06")));
        }
        rows.page(LocalDate.parse("2026-11-06"), "/", "de", "desktop", 4, 3);
        rows.submits(LocalDate.parse("2026-11-06"), 1);
        rows.enquiries(LocalDate.parse("2026-11-03"), 9); // before go-live: not compared
        rows.enquiries(LocalDate.parse("2026-11-06"), 2);
        rows.ingestStats(LocalDate.parse("2026-11-02"), "rejected", "shape", 4);
        rows.ingestStats(LocalDate.parse("2026-11-06"), "duplicate", "", 2);

        mockMvc.perform(get("/api/webanalytics/coverage?from=2026-11-01&to=2026-11-07")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineFrom").value("2026-11-05"))
                .andExpect(jsonPath("$.daily", hasSize(3)))
                .andExpect(jsonPath("$.daily[0].day").value("2026-11-05"))
                .andExpect(jsonPath("$.daily[0].consentedPageviews").value(0))
                .andExpect(jsonPath("$.daily[1].consentedPageviews").value(4))
                .andExpect(jsonPath("$.daily[1].cloudflareRequests").value(100))
                .andExpect(jsonPath("$.daily[1].sampled").value(true))
                .andExpect(jsonPath("$.byPage[0].path").value("/"))
                .andExpect(jsonPath("$.byPage[0].locale").value("de"))
                .andExpect(jsonPath("$.byPage[0].consentedPageviews").value(4))
                .andExpect(jsonPath("$.byPage[0].cloudflareRequests").value(300))
                .andExpect(jsonPath("$.enquiries.consentedSubmits").value(1))
                .andExpect(jsonPath("$.enquiries.totalSubmits").value(2))
                // dataQuality covers the whole range, not only the coverage window.
                .andExpect(jsonPath("$.dataQuality.secondLine.shape").value(4))
                .andExpect(jsonPath("$.dataQuality.secondLine.other").value(0))
                .andExpect(jsonPath("$.dataQuality.duplicates").value(2))
                .andExpect(jsonPath("$.dataQuality.edge.expired").value(0));
    }
}
