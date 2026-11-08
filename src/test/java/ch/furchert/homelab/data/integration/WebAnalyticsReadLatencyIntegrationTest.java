package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/100 §17 WA-3a "< 2 s for 365 days" (local part, Q4 a): 365 final days of seeded aggregates with several paths,
 * clicks, sources and segments; every read endpoint answers a 365-day range in under 2 s. The cluster measurement
 * (Q4 b) follows after rollout.
 */
class WebAnalyticsReadLatencyIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebAnalyticsReadLatencyIntegrationTest.class);
    static final String TOKEN = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));
    static final LocalDate LAST = LocalDate.parse("2026-11-07");
    static final List<String> PATHS = List.of("/", "/automation", "/it", "/about", "/contact", "/projects",
            "/projects/a", "/projects/b", "/rowing");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
    }

    @Test
    void everyReadEndpointAnswersA365DayRangeInUnderTwoSeconds() throws Exception {
        WebAnalyticsTables.clear(jdbc);
        AggregateRows rows = new AggregateRows(jdbc);
        LocalDate first = LAST.minusDays(364);
        for (LocalDate d = first; !d.isAfter(LAST); d = d.plusDays(1)) {
            rows.rollupDay(d, true);
            for (String locale : List.of("de", "en")) {
                for (String device : List.of("desktop", "mobile")) {
                    rows.visits(d, locale, device, 10, 4, 6, 1, 25);
                    for (String path : PATHS) {
                        rows.page(d, path, locale, device, 3, 2);
                    }
                }
            }
            rows.click(d, "/automation", "cta", "automation-hero", 2, 2);
            rows.click(d, "/automation", "faq", "cost", 1, 1);
            rows.section(d, "preise", 3, 1);
            rows.source(d, "search", "google.com", "", 5);
            rows.source(d, "campaign", "", "linkedin", 2);
            rows.conversion(d, "/automation", "search", "social", 1);
            rows.enquiries(d, 1);
            rows.funnel(d, "offer", 1, 10);
            rows.funnel(d, "offer", 2, 4);
            rows.transition(d, "de", "(entrance)", "/", 5);
            rows.transition(d, "de", "/", "/automation", 3);
            rows.groupPath(d, "home > offer > (exit)", 3, 1);
            rows.entryExit(d, "/", "/automation", 3);
            rows.intentSeen(d, "prices", 1);
            rows.dimension(d, "country", "CH", 8);
            rows.cfBaseline(d, "/", "de", 40, false);
            rows.submits(d, 1);
            rows.ingestStats(d, "accepted", "", 20);
        }
        String range = "from=" + first + "&to=" + LAST;
        for (String url : List.of("/api/webanalytics/status", "/api/webanalytics/overview?" + range,
                "/api/webanalytics/pages?" + range, "/api/webanalytics/pages/detail?path=/automation&" + range,
                "/api/webanalytics/clicks?" + range, "/api/webanalytics/sources?" + range,
                "/api/webanalytics/funnels?" + range, "/api/webanalytics/paths?" + range,
                "/api/webanalytics/audience?" + range, "/api/webanalytics/coverage?" + range)) {
            long start = System.nanoTime();
            mockMvc.perform(get(url).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            log.info("[latency] {} {} ms", url.replaceAll("\\?.*", ""), elapsed.toMillis());
            assertThat(elapsed).as(url).isLessThan(Duration.ofSeconds(2));
        }
    }
}
