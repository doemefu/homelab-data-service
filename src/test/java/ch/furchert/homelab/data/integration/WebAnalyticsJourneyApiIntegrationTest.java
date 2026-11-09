package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
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

/** JSON contract of the WA-3b read endpoints and the WA-3a follow-ups (docs/100 §8.1, §8.2, rev 3.5). */
class WebAnalyticsJourneyApiIntegrationTest extends AbstractIntegrationTest {

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
    void funnelsAreZeroFilledAndStartAtTheFirstDayOfTheCurrentDefinition() throws Exception {
        rows.rollupDay(LAST.minusDays(2), true, 1); // former definition: before definitionFrom
        rows.rollupDay(LAST.minusDays(1), true);
        rows.rollupDay(LAST, true);
        rows.funnel(LAST.minusDays(2), "offer", 1, 99);
        rows.funnel(LAST, "offer", 1, 10);
        rows.funnel(LAST, "offer", 2, 6);
        rows.funnel(LAST, "contact_form", 1, 2);

        get("/api/webanalytics/funnels?from=2026-11-01&to=2026-11-07")
                .andExpect(jsonPath("$.definitionVersion").value(RollupPlanner.DEFINITION_VERSION))
                .andExpect(jsonPath("$.definitionFrom").value("2026-11-06"))
                .andExpect(jsonPath("$.range.from").value("2026-11-06"))
                .andExpect(jsonPath("$.range.days").value(2))
                .andExpect(jsonPath("$.funnels[0].id").value("offer"))
                .andExpect(jsonPath("$.funnels[0].steps", hasSize(6)))
                .andExpect(jsonPath("$.funnels[0].steps[*].key", contains("visit", "offer_visit", "reached_prices",
                        "contact_intent", "form_start", "contact_submit")))
                .andExpect(jsonPath("$.funnels[0].steps[0].visits").value(10))
                .andExpect(jsonPath("$.funnels[0].steps[5].visits").value(0))
                .andExpect(jsonPath("$.funnels[0].previous").value(nullValue())) // previous range lies before v2
                .andExpect(jsonPath("$.funnels[1].id").value("contact_form"))
                .andExpect(jsonPath("$.funnels[1].steps", hasSize(3)));
    }

    @Test
    void funnelsAreEmptyWithoutAnyDayOfTheCurrentDefinition() throws Exception {
        rows.rollupDay(LAST, true, 1);
        get("/api/webanalytics/funnels")
                .andExpect(jsonPath("$.definitionFrom").value(nullValue()))
                .andExpect(jsonPath("$.range.days").value(0))
                .andExpect(jsonPath("$.funnels[0].steps[0].visits").value(0));
    }

    @Test
    void definitionFromStartsAfterTheNewestFinalDayOfAnOlderDefinition() throws Exception {
        rows.rollupDay(LAST.minusDays(3), true);    // current definition, then a long rollback ...
        rows.rollupDay(LAST.minusDays(2), true, 1); // ... left a former-definition day after it
        rows.rollupDay(LAST.minusDays(1), true);
        rows.rollupDay(LAST, true);
        get("/api/webanalytics/funnels?from=2026-11-04&to=2026-11-07")
                .andExpect(jsonPath("$.definitionFrom").value("2026-11-06"))
                .andExpect(jsonPath("$.range.from").value("2026-11-06"));
        rows.rollupDay(LAST.plusDays(1), false, 1); // a provisional former-definition day is never served: ignored
        get("/api/webanalytics/paths?from=2026-11-04&to=2026-11-07")
                .andExpect(jsonPath("$.definitionFrom").value("2026-11-06"));
    }

    @Test
    void pathsFilterOnlyTheTransitions() throws Exception {
        rows.rollupDay(LAST, true);
        rows.visits(LAST, "de", "desktop", 4, 0, 0, 0, 4);
        jdbc.sql("UPDATE webanalytics.daily_visits SET contact_intent_visits = 2 WHERE day = :d").param("d", LAST)
                .update();
        rows.groupPath(LAST, "home > offer > (exit)", 3, 1);
        rows.groupPath(LAST, "home > (exit)", 1, 0);
        rows.entryExit(LAST, "/", "/automation", 3);
        rows.intentSeen(LAST, "prices", 2);
        rows.transition(LAST, "de", "(entrance)", "/", 4);
        rows.transition(LAST, "en", "(entrance)", "/it", 5);

        get("/api/webanalytics/paths?from=2026-11-07&to=2026-11-07&locale=de")
                .andExpect(jsonPath("$.filters.applied.locale").value("de"))
                .andExpect(jsonPath("$.groupPaths[0].path").value("home > offer > (exit)"))
                .andExpect(jsonPath("$.groupPaths[0].convertingVisits").value(1))
                .andExpect(jsonPath("$.entryExit[0].entry").value("/"))
                .andExpect(jsonPath("$.seenBeforeContacting.intentVisits").value(2))
                .andExpect(jsonPath("$.seenBeforeContacting.items[*].seen", contains("about", "projects", "prices",
                        "none")))
                .andExpect(jsonPath("$.seenBeforeContacting.items[2].visits").value(2))
                .andExpect(jsonPath("$.transitions", hasSize(1)))
                .andExpect(jsonPath("$.transitions[0].fromPath").value("(entrance)"))
                .andExpect(jsonPath("$.transitions[0].count").value(4));
        get("/api/webanalytics/paths?from=2026-11-07&to=2026-11-07&limit=1")
                .andExpect(jsonPath("$.groupPaths[*].path", contains("home > offer > (exit)", "(other)")))
                .andExpect(jsonPath("$.transitions[1].fromPath").value("(other)"));
    }

    @Test
    void audienceIgnoresFiltersAndUsesTheRepeatViewerBases() throws Exception {
        for (LocalDate d = LAST.minusDays(27); !d.isAfter(LAST); d = d.plusDays(1)) {
            rows.rollupDay(d, true);
        }
        rows.visits(LAST, "de", "desktop", 4, 0, 0, 0, 4);
        rows.visits(LAST, "en", "mobile", 2, 0, 0, 0, 2);
        rows.repeatOfferViewers(LAST, 1);
        rows.dimension(LAST, "country", "CH", 5);
        rows.dimension(LAST, "country", "", 1);
        rows.dimension(LAST, "visitor_type", "new", 3);

        get("/api/webanalytics/audience?locale=de")
                .andExpect(jsonPath("$.filters.ignored", contains("locale")))
                .andExpect(jsonPath("$.newVsReturning.newVisits").value(2 + 1))
                .andExpect(jsonPath("$.repeatOfferViewers.value").value(1))
                .andExpect(jsonPath("$.repeatOfferViewers.basis").value("28d"))
                .andExpect(jsonPath("$.deviceClass[0].value").value("desktop"))
                .andExpect(jsonPath("$.entryLocale[1].value").value("en"))
                .andExpect(jsonPath("$.dimensions.country[0].value").value("CH"))
                .andExpect(jsonPath("$.dimensions.country[1].value").value(nullValue()))
                .andExpect(jsonPath("$.dimensions.visitorType[0].visits").value(3))
                .andExpect(jsonPath("$.dimensions.browserFamily", hasSize(0)));
        get("/api/webanalytics/audience?from=2026-11-01&to=2026-11-07")
                .andExpect(jsonPath("$.repeatOfferViewers.value").value(nullValue()))
                .andExpect(jsonPath("$.repeatOfferViewers.basis").value(nullValue()));
    }

    @Test
    void pageDetailPreviousAndNextFollowTheLimit() throws Exception {
        rows.rollupDay(LAST, true);
        rows.transition(LAST, "de", "(entrance)", "/automation", 5);
        rows.transition(LAST, "de", "/", "/automation", 3);
        rows.transition(LAST, "de", "/it", "/automation", 1);
        rows.transition(LAST, "de", "/automation", "(exit)", 6);
        rows.transition(LAST, "de", "/automation", "/contact", 3);

        get("/api/webanalytics/pages/detail?path=/automation&from=2026-11-07&to=2026-11-07&limit=2")
                .andExpect(jsonPath("$.previous[*].path", contains("(entrance)", "/", "(other)")))
                .andExpect(jsonPath("$.previous[2].count").value(1))
                .andExpect(jsonPath("$.next[*].path", contains("(exit)", "/contact")));
        get("/api/webanalytics/pages/detail?path=/automation&from=2026-11-07&to=2026-11-07")
                .andExpect(jsonPath("$.previous", hasSize(3)));
    }

    @Test
    void clicksRemainderMergesOnlyTheStoredOutboundOther() throws Exception {
        rows.rollupDay(LAST, true);
        rows.click(LAST, "/projects", "outbound", "github.com", 9, 9);
        rows.click(LAST, "/projects", "outbound", "(other)", 2, 2);
        rows.click(LAST, "/", "nav", "(other)", 3, 3);
        rows.click(LAST, "/", "cta", "home-cta", 1, 1);

        get("/api/webanalytics/clicks?from=2026-11-07&to=2026-11-07&limit=2")
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].target").value("github.com"))
                .andExpect(jsonPath("$.items[1].kind").value("nav"))
                .andExpect(jsonPath("$.items[1].target").value("(other)"))
                .andExpect(jsonPath("$.items[2].kind").value(nullValue())) // mixed kinds without a kind filter
                .andExpect(jsonPath("$.items[2].target").value("(other)"))
                .andExpect(jsonPath("$.items[2].path").value(nullValue()))
                .andExpect(jsonPath("$.items[2].area").value(nullValue()))
                .andExpect(jsonPath("$.items[2].pageVisits").value(nullValue()))
                .andExpect(jsonPath("$.items[2].clicks").value(3)) // outbound (other) 2 + cta 1
                .andExpect(jsonPath("$.items[2].visits").value(3));
        get("/api/webanalytics/clicks?kind=outbound&from=2026-11-07&to=2026-11-07&limit=1")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].target").value("github.com"))
                .andExpect(jsonPath("$.items[1].kind").value("outbound")) // the filtered kind
                .andExpect(jsonPath("$.items[1].target").value("(other)"))
                .andExpect(jsonPath("$.items[1].path").value(nullValue()))
                .andExpect(jsonPath("$.items[1].clicks").value(2));
    }

    @Test
    void audienceStartsAtTheFirstDayOfTheCurrentDefinition() throws Exception {
        rows.rollupDay(LAST.minusDays(1), true, 1); // former definition: no WA-3b rows expected, ignored if present
        rows.rollupDay(LAST, true);
        rows.visits(LAST.minusDays(1), "de", "desktop", 7, 0, 0, 0, 7);
        rows.visits(LAST, "de", "desktop", 2, 0, 0, 0, 2);
        rows.dimension(LAST.minusDays(1), "country", "DE", 7);
        rows.dimension(LAST, "country", "CH", 2);

        get("/api/webanalytics/audience?from=2026-11-06&to=2026-11-07")
                .andExpect(jsonPath("$.range.from").value("2026-11-07"))
                .andExpect(jsonPath("$.newVsReturning.newVisits").value(1)) // 2 / 2; the 7 visits of 2026-11-06 are excluded
                .andExpect(jsonPath("$.dimensions.country", hasSize(1)))
                .andExpect(jsonPath("$.dimensions.country[0].value").value("CH"));
        get("/api/webanalytics/audience?from=2026-11-06&to=2026-11-06")
                .andExpect(jsonPath("$.range.from").value(nullValue()))
                .andExpect(jsonPath("$.dimensions.country", hasSize(0)));
    }

    @Test
    void pageDetailPreviousAndNextStartAtTheFirstDayOfTheCurrentDefinition() throws Exception {
        rows.rollupDay(LAST.minusDays(1), true, 1);
        rows.rollupDay(LAST, true);
        rows.transition(LAST.minusDays(1), "de", "/", "/automation", 8);
        rows.transition(LAST, "de", "(entrance)", "/automation", 2);

        get("/api/webanalytics/pages/detail?path=/automation&from=2026-11-06&to=2026-11-07")
                .andExpect(jsonPath("$.previous[*].path", contains("(entrance)")))
                .andExpect(jsonPath("$.previous[0].count").value(2));
        get("/api/webanalytics/pages/detail?path=/automation&from=2026-11-06&to=2026-11-06")
                .andExpect(jsonPath("$.previous", hasSize(0)))
                .andExpect(jsonPath("$.next", hasSize(0)));
    }

    @Test
    void enquiriesHideProvisionalDaysButCountDaysWithoutARollup() throws Exception {
        rows.rollupDay(LAST.minusDays(2), true);
        rows.rollupDay(LAST.minusDays(1), false); // provisional below lastCompleteDay
        rows.rollupDay(LAST, true);
        rows.enquiries(LAST.minusDays(3), 1);      // no rollup_day row (before the first rollup)
        rows.enquiries(LAST.minusDays(1), 5);      // hidden
        rows.enquiries(LAST, 2);

        get("/api/webanalytics/overview?from=2026-11-04&to=2026-11-07")
                .andExpect(jsonPath("$.current.enquiriesTotal").value(3))
                .andExpect(jsonPath("$.daily[0].enquiries").value(1))
                .andExpect(jsonPath("$.daily[2].enquiries").value(0));
    }

    @Test
    void coverageComparesNothingWhileTheGoLiveDateIsUnset() throws Exception {
        rows.rollupDay(LAST, true);
        rows.cfBaseline(LAST, "/", "de", 40, false);
        rows.ingestStats(LAST, "edge", "bot", 3);
        get("/api/webanalytics/coverage")
                .andExpect(jsonPath("$.baselineFrom").value(nullValue()))
                .andExpect(jsonPath("$.daily", hasSize(0)))
                .andExpect(jsonPath("$.byPage", hasSize(0)))
                .andExpect(jsonPath("$.enquiries.totalSubmits").value(0))
                .andExpect(jsonPath("$.dataQuality.edge.bot").value(3))
                .andExpect(jsonPath("$.dataQuality.cappedDays").value(0));
    }

    private ResultActions get(String url) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
    }
}
