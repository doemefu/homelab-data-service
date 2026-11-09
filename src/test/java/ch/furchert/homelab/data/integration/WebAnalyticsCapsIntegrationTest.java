package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.MutableClock;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import ch.furchert.homelab.data.webanalytics.ingest.IngestCaps;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.EVENTS;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.batch;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.event;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.writeToken;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.zeroEdgeDrops;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WA-5 caps end to end (docs/100 §6.3, §6.4; #56 comments 2, 3, 5) with small caps and a pinned clock: the capped 429
 * body, all-or-nothing (nothing stored, no stats, no {@code edgeDrops} counted), the {@code capped} stats row, priority-
 * only batches while capped, the day scope, the per-visitor cap with goals exempt, and the gauges. Each test moves the
 * clock to a fresh UTC day and re-seeds the ledger from the emptied table.
 */
@TestPropertySource(properties = {"webanalytics.ingest.daily-cap=6", "webanalytics.ingest.hourly-cap=4",
        "webanalytics.ingest.visitor-daily-cap=3"})
@Import(WebAnalyticsCapsIntegrationTest.PinnedClock.class)
class WebAnalyticsCapsIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class PinnedClock {
        @Bean
        @Primary
        MutableClock pinnedClock() {
            return new MutableClock(Instant.parse("2026-11-09T10:30:00Z"));
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    IngestCaps caps;
    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void freshDay() {
        WebAnalyticsTables.clear(jdbc);
        clock.set(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(Duration.ofDays(1))
                .plus(Duration.ofMinutes(630))); // next day, 10:30Z
        caps.seedStarted();
        caps.seedCompleted(Map.of());
    }

    @Test
    void hourCapAnswers429WithTheExactBodyAndStoresAndCountsNothing() throws Exception {
        send(batch(pageviews(4))).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(4));
        double edgeBefore = registry.get("webanalytics.edge.dropped").tag("reason", "bot").counter().count();
        Map<String, Object> drops = zeroEdgeDrops();
        drops.put("bot", 5);
        List<Map<String, Object>> events = new ArrayList<>(pageviews(2));
        events.add(event("goal", clock.instant()));

        String retryAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plus(Duration.ofHours(1))
                .toString();
        send(batch(events, drops)).andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"accepted\":0,\"duplicates\":0,\"rejected\":0,\"capped\":true,"
                        + "\"capScope\":\"hour\",\"retryAt\":\"" + retryAt + "\"}", true));

        assertThat(count("SELECT count(*) FROM webanalytics.events")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM webanalytics.daily_ingest_stats WHERE stage = 'edge'")).isZero();
        assertThat(registry.get("webanalytics.edge.dropped").tag("reason", "bot").counter().count())
                .isEqualTo(edgeBefore);
        assertThat(count("SELECT coalesce(sum(events), 0) FROM webanalytics.daily_ingest_stats WHERE stage = 'capped' "
                + "AND reason = 'hour'")).isEqualTo(2);
        assertThat(registry.get("webanalytics.ingest.capped").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("webanalytics.events.today").gauge().value()).isEqualTo(4.0);

        // The gauge returns to 0 at retryAt without any traffic.
        clock.set(Instant.parse(retryAt));
        assertThat(registry.get("webanalytics.ingest.capped").gauge().value()).isZero();
    }

    @Test
    void priorityOnlyBatchesAreAcceptedWhileCapped() throws Exception {
        send(batch(pageviews(4))).andExpect(status().isOk());
        send(batch(pageviews(1))).andExpect(status().isTooManyRequests());
        send(batch(List.of(event("goal", clock.instant()), event("enquiry", clock.instant()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.capped").value(false));
        assertThat(count("SELECT submissions FROM webanalytics.daily_enquiries")).isEqualTo(1);
    }

    @Test
    void dailyCapAnswersWithTheDayScopeAndMidnightUtc() throws Exception {
        send(batch(pageviews(4))).andExpect(status().isOk());
        clock.advance(Duration.ofHours(1));
        send(batch(pageviews(2))).andExpect(status().isOk());
        String midnight = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(Duration.ofDays(1))
                .toString();
        send(batch(pageviews(1))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.capScope").value("day"))
                .andExpect(jsonPath("$.retryAt").value(midnight));
    }

    @Test
    void perVisitorCapRejectsTheExcessAndGoalsStayExempt() throws Exception {
        UUID visitor = UUID.randomUUID();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(of(visitor, "pageview"));
        }
        double before = registry.get("webanalytics.events.rejected").tag("reason", "visitor_cap").counter().count();
        send(batch(events)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(1));
        assertThat(registry.get("webanalytics.events.rejected").tag("reason", "visitor_cap").counter().count() - before)
                .isEqualTo(1);
        assertThat(count("SELECT events FROM webanalytics.daily_ingest_stats WHERE stage = 'rejected' "
                + "AND reason = 'visitor_cap'")).isEqualTo(1);

        send(batch(List.of(of(visitor, "goal")))).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        send(batch(List.of(of(visitor, "click")))).andExpect(status().isOk()).andExpect(jsonPath("$.rejected").value(1));
    }

    @Test
    void perVisitorCountsAreSeededFromStoredRowsOnFirstSight() throws Exception {
        UUID visitor = UUID.randomUUID();
        send(batch(List.of(of(visitor, "pageview"), of(visitor, "pageview")))).andExpect(status().isOk());
        caps.seedStarted();
        caps.seedCompleted(Map.of()); // forgets the per-visitor map, as after a restart
        send(batch(List.of(of(visitor, "pageview"), of(visitor, "pageview"))))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1));
    }

    private List<Map<String, Object>> pageviews(int n) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(event("pageview", clock.instant()));
        }
        return events;
    }

    private Map<String, Object> of(UUID visitor, String type) {
        Map<String, Object> event = new LinkedHashMap<>(event(type, clock.instant()));
        event.put("visitorId", visitor.toString());
        return event;
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(EVENTS).header("Authorization", "Bearer " + writeToken())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String sql) {
        Long n = jdbc.sql(sql).query(Long.class).optional().orElse(0L);
        return n == null ? 0 : n;
    }
}
