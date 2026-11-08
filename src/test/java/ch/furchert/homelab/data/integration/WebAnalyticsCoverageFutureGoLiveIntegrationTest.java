package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A go-live date in the future compares nothing yet: {@code baselineFrom} is {@code null} (docs/100 §8.2, rev 3.5). */
@TestPropertySource(properties = "webanalytics.coverage.go-live-date=2099-01-01")
class WebAnalyticsCoverageFutureGoLiveIntegrationTest extends AbstractIntegrationTest {

    static final String TOKEN = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    @Test
    void aFutureGoLiveDateLeavesTheCoverageWindowEmpty() throws Exception {
        WebAnalyticsTables.clear(jdbc);
        AggregateRows rows = new AggregateRows(jdbc);
        LocalDate day = LocalDate.parse("2026-11-07");
        rows.rollupDay(day, true);
        rows.cfBaseline(day, "/", "de", 40, false);
        rows.page(day, "/", "de", "desktop", 4, 3);

        mockMvc.perform(get("/api/webanalytics/coverage?from=2026-11-07&to=2026-11-07")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineFrom").value(nullValue()))
                .andExpect(jsonPath("$.daily", hasSize(0)))
                .andExpect(jsonPath("$.byPage", hasSize(0)));
    }
}
