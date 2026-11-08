package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON contract of the WA-3a read API (docs/100 §8.1, §8.2, rev 3.4) on seeded aggregate rows: ranges, compare,
 * filters, final-days-only, limits and {@code (other)}, the empty state and parameter errors.
 */
class WebAnalyticsReadApiIntegrationTest extends AbstractIntegrationTest {

    static final String TOKEN = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));
    static final LocalDate LAST = LocalDate.parse("2026-11-07");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    AggregateRows rows;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
        rows = new AggregateRows(jdbc);
    }

    @Test
    void emptyStateBeforeTheFirstFinalDay() throws Exception {
        get("/api/webanalytics/overview")
                .andExpect(jsonPath("$.range.from").value(nullValue()))
                .andExpect(jsonPath("$.range.to").value(nullValue()))
                .andExpect(jsonPath("$.range.days").value(0))
                .andExpect(jsonPath("$.range.lastCompleteDay").value(nullValue()))
                .andExpect(jsonPath("$.range.previous").value(nullValue()))
                .andExpect(jsonPath("$.current.visits").value(0))
                .andExpect(jsonPath("$.previous").value(nullValue()))
                .andExpect(jsonPath("$.visitors").value(nullValue()))
                .andExpect(jsonPath("$.daily", hasSize(0)));
        get("/api/webanalytics/status")
                .andExpect(jsonPath("$.lastCompleteDay").value(nullValue()))
                .andExpect(jsonPath("$.provisionalDay").value(nullValue()))
                .andExpect(jsonPath("$.rawRetentionStart").value(nullValue()))
                .andExpect(jsonPath("$.definitionVersion").value(1))
                .andExpect(jsonPath("$.ingest.dailyCap").value(nullValue()))
                .andExpect(jsonPath("$.ingest.capped").value(nullValue()));
        get("/api/webanalytics/pages").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void defaultRangeIs28DaysEndingAtTheLastCompleteDayAndProvisionalDaysAreNeverServed() throws Exception {
        seedFinalDays(LocalDate.parse("2026-09-01"), LAST, 2);
        // An old provisional day below lastCompleteDay and yesterday's provisional row: both carry data, neither is served.
        jdbc.sql("UPDATE webanalytics.rollup_day SET final = false WHERE day = '2026-10-20'").update();
        rows.rollupDay(LAST.plusDays(1), false);
        rows.visits(LAST.plusDays(1), "de", "desktop", 50, 0, 0, 0, 50);

        get("/api/webanalytics/overview")
                .andExpect(jsonPath("$.range.from").value("2026-10-11"))
                .andExpect(jsonPath("$.range.to").value("2026-11-07"))
                .andExpect(jsonPath("$.range.days").value(28))
                .andExpect(jsonPath("$.range.lastCompleteDay").value("2026-11-07"))
                .andExpect(jsonPath("$.range.previous.from").value("2026-09-13"))
                .andExpect(jsonPath("$.range.previous.to").value("2026-10-10"))
                .andExpect(jsonPath("$.current.visits").value(27 * 2))
                .andExpect(jsonPath("$.daily", hasSize(28)))
                .andExpect(jsonPath("$.daily[9].day").value("2026-10-20"))
                .andExpect(jsonPath("$.daily[9].visits").value(0));
        get("/api/webanalytics/overview?to=2026-11-08")
                .andExpect(jsonPath("$.range.to").value("2026-11-07"));
        get("/api/webanalytics/overview?from=2026-11-08")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value(nullValue()))
                .andExpect(jsonPath("$.range.days").value(0));
        get("/api/webanalytics/status")
                .andExpect(jsonPath("$.lastCompleteDay").value("2026-11-07"))
                .andExpect(jsonPath("$.provisionalDay").value("2026-11-08"))
                .andExpect(jsonPath("$.collectors[0].name").value("webanalytics-rollup"));
    }

    @Test
    void compareNoneAndWholeMonthBasis() throws Exception {
        seedFinalDays(LocalDate.parse("2026-09-01"), LAST, 1);
        rows.monthly(LocalDate.parse("2026-10-01"), 42);
        rows.monthly(LocalDate.parse("2026-09-01"), 30);

        get("/api/webanalytics/overview?from=2026-10-01&to=2026-10-31")
                .andExpect(jsonPath("$.range.previous.from").value("2026-09-01"))
                .andExpect(jsonPath("$.range.previous.to").value("2026-09-30"))
                .andExpect(jsonPath("$.visitors.current.value").value(42))
                .andExpect(jsonPath("$.visitors.current.basis").value("month"))
                .andExpect(jsonPath("$.visitors.previous.value").value(30));
        get("/api/webanalytics/overview?from=2026-10-01&to=2026-10-31&compare=none")
                .andExpect(jsonPath("$.range.previous").value(nullValue()))
                .andExpect(jsonPath("$.previous").value(nullValue()))
                .andExpect(jsonPath("$.visitors.previous").value(nullValue()));
    }

    @Test
    void sevenDayVisitorsAndFiltersAreEchoed() throws Exception {
        seedFinalDays(LocalDate.parse("2026-10-01"), LAST, 1);
        rows.visitors(LAST, 3, 11, 25);
        rows.visitors(LAST.minusDays(7), 2, 9, 20);
        rows.visits(LAST, "en", "mobile", 5, 0, 0, 0, 5);

        get("/api/webanalytics/overview?from=2026-11-01&to=2026-11-07")
                .andExpect(jsonPath("$.visitors.current.value").value(11))
                .andExpect(jsonPath("$.visitors.current.basis").value("7d"))
                .andExpect(jsonPath("$.visitors.previous.value").value(9))
                .andExpect(jsonPath("$.filters.ignored", hasSize(0)));
        get("/api/webanalytics/overview?from=2026-11-01&to=2026-11-07&locale=en&device=mobile")
                .andExpect(jsonPath("$.current.visits").value(5))
                .andExpect(jsonPath("$.visitors").value(nullValue()))
                .andExpect(jsonPath("$.filters.applied.locale").value("en"))
                .andExpect(jsonPath("$.filters.applied.device").value("mobile"));
        get("/api/webanalytics/status?locale=en")
                .andExpect(jsonPath("$.filters.ignored", contains("locale")));
    }

    @Test
    void parameterErrors() throws Exception {
        seedFinalDays(LocalDate.parse("2026-10-01"), LAST, 1);
        expectCode("/api/webanalytics/overview?from=2026-11-05&to=2026-11-01", "invalid_window");
        expectCode("/api/webanalytics/overview?from=yesterday", "invalid_window");
        expectCode("/api/webanalytics/overview?from=2025-01-01&to=2026-11-07", "invalid_window");
        expectCode("/api/webanalytics/overview?locale=fr", "invalid_parameter");
        expectCode("/api/webanalytics/overview?compare=last", "invalid_parameter");
        expectCode("/api/webanalytics/pages?limit=0", "invalid_parameter");
        expectCode("/api/webanalytics/pages?limit=101", "invalid_parameter");
        expectCode("/api/webanalytics/clicks?kind=hover", "invalid_parameter");
        expectCode("/api/webanalytics/pages/detail?path=/de/Automation", "invalid_parameter");
        expectCode("/api/webanalytics/pages/detail", "invalid_parameter");
    }

    @Test
    void pagesLimitFoldsTheRemainderIntoOther() throws Exception {
        seedFinalDays(LAST, LAST, 1);
        rows.page(LAST, "/", "de", "desktop", 10, 8);
        rows.page(LAST, "/automation", "de", "desktop", 7, 6);
        rows.page(LAST, "/it", "de", "desktop", 3, 3);
        rows.page(LAST, "/about", "de", "desktop", 1, 1);

        get("/api/webanalytics/pages?from=2026-11-07&to=2026-11-07&limit=2")
                .andExpect(jsonPath("$.items[*].path", contains("/", "/automation", "(other)")))
                .andExpect(jsonPath("$.items[2].pageviews").value(4))
                .andExpect(jsonPath("$.items[0].pageviewsByLocale.de").value(10))
                .andExpect(jsonPath("$.items[0].engagedHistogram", hasSize(8)))
                .andExpect(jsonPath("$.items[0].scrollReached.p50").value(1));
    }

    @Test
    void pageDetailForAutomationAndForAnotherPath() throws Exception {
        seedFinalDays(LAST.minusDays(1), LAST, 1);
        rows.page(LAST, "/automation", "de", "desktop", 7, 6);
        rows.section(LAST, "preise", 4, 2);
        rows.click(LAST, "/automation", "faq", "cost", 3, 2);
        rows.click(LAST, "/automation", "cta", "automation-hero", 2, 2);

        get("/api/webanalytics/pages/detail?path=/automation&from=2026-11-06&to=2026-11-07")
                .andExpect(jsonPath("$.summary.pageviews").value(7))
                .andExpect(jsonPath("$.previous").value(nullValue()))
                .andExpect(jsonPath("$.next").value(nullValue()))
                .andExpect(jsonPath("$.clicks", hasSize(2)))
                .andExpect(jsonPath("$.sections.reached", hasSize(8)))
                .andExpect(jsonPath("$.sections.reached[3].section").value("preise"))
                .andExpect(jsonPath("$.sections.reached[3].pageviews").value(4))
                .andExpect(jsonPath("$.sections.deepestAtExit[3].visits").value(2))
                .andExpect(jsonPath("$.faqOpens[0].faq").value("cost"))
                .andExpect(jsonPath("$.faqOpens[0].opens").value(3))
                .andExpect(jsonPath("$.daily", hasSize(2)))
                .andExpect(jsonPath("$.daily[0].pageviews").value(0));
        get("/api/webanalytics/pages/detail?path=/it")
                .andExpect(jsonPath("$.summary.path").value("/it"))
                .andExpect(jsonPath("$.summary.pageviews").value(0))
                .andExpect(jsonPath("$.sections").value(nullValue()))
                .andExpect(jsonPath("$.faqOpens").value(nullValue()));
    }

    @Test
    void clicksCarryPageVisitsAndNullTargetsForMailto() throws Exception {
        seedFinalDays(LAST, LAST, 1);
        rows.page(LAST, "/contact", "de", "desktop", 5, 4);
        rows.click(LAST, "/contact", "mailto", "", 2, 2);
        rows.click(LAST, "/contact", "outbound", "github.com", 1, 1);

        get("/api/webanalytics/clicks?from=2026-11-07&to=2026-11-07")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind").value("mailto"))
                .andExpect(jsonPath("$.items[0].target").value(nullValue()))
                .andExpect(jsonPath("$.items[0].pageVisits").value(4));
        get("/api/webanalytics/clicks?from=2026-11-07&to=2026-11-07&kind=outbound")
                .andExpect(jsonPath("$.items[*].target", contains("github.com")));
    }

    @Test
    void sourcesListAllChannelsAndMergeTheRemainderWithStoredOther() throws Exception {
        seedFinalDays(LAST, LAST, 1);
        rows.source(LAST, "search", "google.com", "", 5);
        rows.source(LAST, "referral", "example.org", "", 2);
        rows.source(LAST, "referral", "(other)", "", 1);
        rows.source(LAST, "campaign", "", "linkedin", 3);
        rows.conversion(LAST, "/contact", "direct", "", 1);
        rows.conversion(LAST, "/automation", "search", "social", 2);

        get("/api/webanalytics/sources?from=2026-11-07&to=2026-11-07&limit=1")
                .andExpect(jsonPath("$.channels[*].channel",
                        contains("campaign", "ai", "search", "social", "referral", "direct", "internal")))
                .andExpect(jsonPath("$.channels[2].visits").value(5))
                .andExpect(jsonPath("$.channels[1].visits").value(0))
                .andExpect(jsonPath("$.referrers[*].domain", contains("google.com", "(other)")))
                .andExpect(jsonPath("$.referrers[1].visits").value(3))
                .andExpect(jsonPath("$.campaigns[0].source").value("linkedin"))
                .andExpect(jsonPath("$.campaigns[0].medium").value(nullValue()))
                .andExpect(jsonPath("$.converters.byLandingChannel[0].channel").value("search"))
                .andExpect(jsonPath("$.converters.byFirstChannel[*].channel", contains("social", null)))
                .andExpect(jsonPath("$.converters.byEntryPath[0].path").value("/automation"));
    }

    private void seedFinalDays(LocalDate from, LocalDate to, int visitsPerDay) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            rows.rollupDay(d, true);
            rows.visits(d, "de", "desktop", visitsPerDay, 0, 0, 0, visitsPerDay);
        }
    }

    private ResultActions get(String url) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }

    private void expectCode(String url, String code) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }
}
