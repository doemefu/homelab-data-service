package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures.Case;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.batch;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/webanalytics/events} end to end (docs/100 §6.2, §7.2, §7.9; C13): storage, dedupe,
 * per-event rejection, batch-level 400/413/415, edge drops and the enquiry counter. Metric assertions are deltas
 * (the registry is shared with other test classes); ids are fresh per test.
 */
class WebAnalyticsIngestIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
    }

    @Test
    void validBatchOfEveryAnalyticsTypeIsStored() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> pageview = event("pageview", now);
        List<Map<String, Object>> events = List.of(pageview, event("engagement", now), event("click", now), event("goal", now));
        double accepted = counter("webanalytics.events.accepted");

        send(batch(events)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(4))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.capped").value(false));

        assertThat(counter("webanalytics.events.accepted") - accepted).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isEqualTo(4L);
        Map<String, Object> row = jdbc.sql("SELECT * FROM webanalytics.events WHERE event_id = CAST(:id AS uuid)")
                .param("id", pageview.get("eventId")).query().singleRow();
        assertThat(row).containsEntry("event_type", "pageview")
                .containsEntry("visitor_id", UUID.fromString((String) pageview.get("visitorId")))
                .containsEntry("seq", 0)
                .containsEntry("path", "/automation")
                .containsEntry("locale", "de")
                .containsEntry("landing", true)
                .containsEntry("referrer_domain", "google.com")
                .containsEntry("utm_campaign", "2026-11-ki")
                .containsEntry("country", "CH")
                .containsEntry("viewport", "1024-1439")
                .containsEntry("source", "furchert-ch");
        assertThat(stats("accepted", "")).isEqualTo(4L);
    }

    @Test
    void specExampleBodyIsAccepted() throws Exception {
        String body = """
                { "events": [ { "eventId": "%s", "type": "pageview", "occurredAt": "%s", "receivedAt": "%s",
                                "visitorId": "%s", "pageviewId": "%s", "seq": 0, "path": "/automation", "locale": "de",
                                "landing": true, "referrerDomain": "google.com", "utmSource": "linkedin", "utmMedium": "social", "utmCampaign": "2026-11-ki",
                                "country": "CH", "deviceClass": "desktop", "browserFamily": "firefox", "osFamily": "macos", "viewport": "1024-1439",
                                "engagedMs": null, "scrollMax": null, "fits": null, "sectionMask": null, "formStarted": null,
                                "clickKind": null, "clickTarget": null, "clickArea": null, "goal": null } ],
                  "edgeDrops": { "bot": 0, "signed_in": 0, "consent": 0, "invalid": 0, "path": 0, "rate_limited": 0, "mint_limited": 0, "overflow": 0, "expired": 0 } }
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS),
                Instant.now().truncatedTo(ChronoUnit.MILLIS), UUID.randomUUID(), UUID.randomUUID());
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
    }

    @Test
    void replayedBatchCountsDuplicates() throws Exception {
        Instant now = Instant.now();
        String body = batch(List.of(event("pageview", now), event("click", now), event("goal", now)));
        send(body).andExpect(jsonPath("$.accepted").value(3));
        double duplicates = counter("webanalytics.events.duplicate");

        send(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(3));

        assertThat(counter("webanalytics.events.duplicate") - duplicates).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isEqualTo(3L);
        assertThat(stats("duplicate", "")).isEqualTo(3L);
    }

    @Test
    void duplicateEventIdInsideOneBatchIsCountedOnce() throws Exception {
        Map<String, Object> click = event("click", Instant.now());
        send(batch(List.of(click, click))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
    }

    @Test
    void pageviewSeqReusedByAnotherEventIdIsRejectedAsOther() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> first = event("click", now);
        send(batch(List.of(first))).andExpect(jsonPath("$.accepted").value(1));
        double rejected = counter("webanalytics.events.rejected", "reason", "other");

        Map<String, Object> reuse = event("click", now, Map.of("pageviewId", first.get("pageviewId"),
                "clickTarget", "automation-final"));
        Map<String, Object> inBatchFirst = event("click", now);
        Map<String, Object> inBatchReuse = event("click", now, Map.of("pageviewId", inBatchFirst.get("pageviewId")));
        send(batch(List.of(reuse, inBatchFirst, inBatchReuse))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(2));

        assertThat(counter("webanalytics.events.rejected", "reason", "other") - rejected).isEqualTo(2);
        assertThat(jdbc.sql("SELECT click_target FROM webanalytics.events WHERE event_id = CAST(:id AS uuid)")
                .param("id", first.get("eventId")).query(String.class).single()).isEqualTo("automation-hero");
        assertThat(stats("rejected", "other")).isEqualTo(2L);
    }

    @Test
    void invalidEventIsRejectedAloneAndCountedByReason() throws Exception {
        Instant now = Instant.now();
        double enumRejects = counter("webanalytics.events.rejected", "reason", "enum");
        send(batch(List.of(event("pageview", now), event("pageview", now, Map.of("locale", "fr")), event("click", now))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1));
        assertThat(counter("webanalytics.events.rejected", "reason", "enum") - enumRejects).isEqualTo(1);
        assertThat(stats("rejected", "enum")).isEqualTo(1L);
    }

    @Test
    void sharedFixturesGiveTheExpectedOutcomeInOneBatch() throws Exception {
        List<Case> cases = WebAnalyticsFixtures.cases(Instant.now());
        long expectedAccepted = cases.stream().filter(Case::accepted)
                .filter(c -> !"enquiry".equals(c.event().get("type"))).count();
        Map<String, Long> expectedRejects = cases.stream().filter(c -> !c.accepted())
                .collect(Collectors.groupingBy(Case::reason, Collectors.counting()));
        Map<String, Double> before = new LinkedHashMap<>();
        expectedRejects.keySet().forEach(r -> before.put(r, counter("webanalytics.events.rejected", "reason", r)));
        double enquiries = counter("webanalytics.enquiries");

        // A CHECK violation would make this a 500: the validator must be a strict superset of the V6 CHECKs.
        send(batch(cases.stream().map(Case::event).toList())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(expectedAccepted))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(cases.size() - cases.stream().filter(Case::accepted).count()));

        expectedRejects.forEach((reason, count) -> assertThat(
                counter("webanalytics.events.rejected", "reason", reason) - before.get(reason)).as(reason)
                .isEqualTo(count.doubleValue()));
        assertThat(counter("webanalytics.enquiries") - enquiries).isEqualTo(1);
    }

    @Test
    void mailtoAndTelTargetsAreRejected() throws Exception {
        Instant now = Instant.now();
        send(batch(List.of(
                event("click", now, Map.of("clickKind", "mailto", "clickTarget", "someone@example.com")),
                event("click", now, Map.of("clickKind", "tel", "clickTarget", "+41000000000")),
                event("click", now, nullTarget("mailto")),
                event("click", now, nullTarget("tel")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events WHERE click_target LIKE '%@%' OR click_target LIKE '+%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void timeWindowViolationsAreRejected() throws Exception {
        Instant now = Instant.now();
        double timeRejects = counter("webanalytics.events.rejected", "reason", "time");
        send(batch(List.of(
                event("goal", now, Map.of("occurredAt", "now-125m", "receivedAt", "now-121m")),
                event("goal", now, Map.of("occurredAt", "now+2m", "receivedAt", "now+2m")),
                event("goal", now, Map.of("occurredAt", "now-1m", "receivedAt", "now-2m")),
                event("goal", now, Map.of("occurredAt", "now-17m", "receivedAt", "now-1m")),
                event("goal", now, Map.of("occurredAt", "now-15m", "receivedAt", "now-0m")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(4));
        assertThat(counter("webanalytics.events.rejected", "reason", "time") - timeRejects).isEqualTo(4);
    }

    @Test
    void schemaViolationsAre400WithoutEchoingInput() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> withUnknown = new LinkedHashMap<>(event("click", now));
        withUnknown.put("leakMarker7", "secret-marker-value");
        expectBadRequest(batch(List.of(withUnknown)), "secret-marker-value");
        expectBadRequest(batch(List.of()).replaceFirst("\\{", "{\"leakMarker7\":1,"), "leakMarker7");
        Map<String, Object> drops = WebAnalyticsFixtures.zeroEdgeDrops();
        drops.put("leakMarker7", 0);
        expectBadRequest(batch(List.of(), drops), "leakMarker7");
        Map<String, Object> missing = new LinkedHashMap<>(event("enquiry", now));
        missing.remove("goal");
        expectBadRequest(batch(List.of(missing)), null);
        expectBadRequest(batch(List.of()).replaceFirst("\\{", "{\"events\":[],"), null); // duplicate key
        expectBadRequest(batch(List.of()) + " trailing", "trailing");
        expectBadRequest("{\"events\": " + "[".repeat(9) + "]".repeat(9) + ", \"edgeDrops\": null}", null);
        Map<String, Object> longString = new LinkedHashMap<>(event("click", now));
        longString.put("clickTarget", "x".repeat(10_000));
        expectBadRequest(batch(List.of(longString)), "xxxxxxxxxx");
        expectBadRequest("{\"events\": [", null);
        expectBadRequest("{\"events\": null, \"edgeDrops\": null}", null);
        Map<String, Object> wrongType = new LinkedHashMap<>(event("click", now));
        wrongType.put("seq", "1");
        expectBadRequest(batch(List.of(wrongType)), null);
        Map<String, Object> negativeDrops = WebAnalyticsFixtures.zeroEdgeDrops();
        negativeDrops.put("bot", -1);
        expectBadRequest(batch(List.of(), negativeDrops), null);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isZero();
    }

    @Test
    void oversizedBodyAndTooManyEventsAre413() throws Exception {
        Map<String, Object> enquiry = event("enquiry", Instant.now());
        String oversized = batch(Collections.nCopies(150, enquiry));
        assertThat(oversized.length()).isGreaterThan(65_536);
        send(oversized).andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
        send(batch(Collections.nCopies(201, enquiry))).andExpect(status().isContentTooLarge());
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                        .header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(batch(List.of())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("invalid_parameter"));
    }

    @Test
    void edgeDropsWithoutEventsAreCounted() throws Exception {
        Map<String, Object> drops = WebAnalyticsFixtures.zeroEdgeDrops();
        drops.put("bot", 3);
        drops.put("signed_in", 2);
        double bot = counter("webanalytics.edge.dropped", "reason", "bot");
        double signedIn = counter("webanalytics.edge.dropped", "reason", "signed_in");

        send(batch(List.of(), drops)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(0));

        assertThat(counter("webanalytics.edge.dropped", "reason", "bot") - bot).isEqualTo(3);
        assertThat(counter("webanalytics.edge.dropped", "reason", "signed_in") - signedIn).isEqualTo(2);
        assertThat(stats("edge", "bot")).isEqualTo(3L);
        assertThat(stats("edge", "signed_in")).isEqualTo(2L);
        assertThat(stats("edge", "consent")).isNull(); // zero counts write no row
    }

    @Test
    void enquiryIncrementsDailyEnquiriesOnlyAndOnceWithinTwoHours() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> enquiry = event("enquiry", now);
        LocalDate day = LocalDate.ofInstant(Instant.parse((String) enquiry.get("occurredAt")), ZURICH);
        double enquiries = counter("webanalytics.enquiries");
        double accepted = counter("webanalytics.events.accepted");

        send(batch(List.of(enquiry))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(0));
        send(batch(List.of(enquiry))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(0));

        assertThat(enquiriesOn(day)).isEqualTo(1L);
        assertThat(counter("webanalytics.enquiries") - enquiries).isEqualTo(1);
        assertThat(counter("webanalytics.events.accepted") - accepted).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.daily_ingest_stats WHERE events > 0")
                .query(Long.class).single()).isZero();
    }

    @Test
    void enquiryWithANonCoreFieldIsRejected() throws Exception {
        Instant now = Instant.now();
        double shape = counter("webanalytics.events.rejected", "reason", "shape");
        send(batch(List.of(event("enquiry", now, Map.of("path", "/contact")),
                event("enquiry", now, Map.of("visitorId", UUID.randomUUID().toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(2));
        assertThat(counter("webanalytics.events.rejected", "reason", "shape") - shape).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.daily_enquiries").query(Long.class).single()).isZero();
    }

    @Test
    void concurrentIdenticalBatchesStoreEveryEventOnce() throws Exception {
        Instant now = Instant.now();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(event("click", now));
        }
        Map<String, Object> enquiry = event("enquiry", now);
        events.add(enquiry);
        String body = batch(events);
        LocalDate day = LocalDate.ofInstant(Instant.parse((String) enquiry.get("occurredAt")), ZURICH);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return send(body).andReturn();
                }));
            }
            start.countDown();
            int accepted = 0;
            int duplicates = 0;
            for (Future<MvcResult> result : results) {
                MvcResult mvc = result.get(30, TimeUnit.SECONDS);
                assertThat(mvc.getResponse().getStatus()).isEqualTo(200);
                Map<?, ?> json = WebAnalyticsFixtures.JSON.readValue(mvc.getResponse().getContentAsString(), Map.class);
                accepted += (Integer) json.get("accepted");
                duplicates += (Integer) json.get("duplicates");
            }
            assertThat(accepted).isEqualTo(5);
            assertThat(accepted + duplicates).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.events").query(Long.class).single()).isEqualTo(5L);
        assertThat(enquiriesOn(day)).isEqualTo(1L);
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                .header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void expectBadRequest(String body, String mustNotEcho) throws Exception {
        ResultActions result = send(body).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.detail").value("The request body is not a valid web analytics batch"));
        if (mustNotEcho != null) {
            result.andExpect(content().string(not(containsString(mustNotEcho))));
        }
    }

    private static Map<String, Object> nullTarget(String kind) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("clickKind", kind);
        overrides.put("clickTarget", null);
        return overrides;
    }

    private double counter(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        assertThat(counter).as(name).isNotNull();
        return counter.count();
    }

    private Long stats(String stage, String reason) {
        return jdbc.sql("""
                        SELECT events FROM webanalytics.daily_ingest_stats
                        WHERE day = :day AND stage = :stage AND reason = :reason
                        """)
                .param("day", LocalDate.now(ZURICH)).param("stage", stage).param("reason", reason)
                .query(Long.class).optional().orElse(null);
    }

    private Long enquiriesOn(LocalDate day) {
        return jdbc.sql("SELECT submissions FROM webanalytics.daily_enquiries WHERE day = :day")
                .param("day", day).query(Long.class).optional().orElse(0L);
    }
}
