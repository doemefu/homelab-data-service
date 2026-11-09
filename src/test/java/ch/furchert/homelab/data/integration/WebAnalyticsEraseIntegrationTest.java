package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.RawEventRows;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.EVENTS;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.batch;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.event;
import static ch.furchert.homelab.data.support.WebAnalyticsFixtures.writeToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/webanalytics/visitors/{visitorId}} end to end (docs/100 §6.5, Rev 3.2; WA-5 plan rulings R2, R7):
 * only that id, 204 also when nothing matched and on repeat, 400 for a bad id, tombstone ({@code erased}) so a batch
 * after the DELETE leaves 0 rows, batched deletes beyond one batch, the barrier against in-flight batches, access
 * control, and no id in any problem body or log line.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebAnalyticsEraseIntegrationTest extends AbstractIntegrationTest {

    private static final String TEMPLATE = "/api/webanalytics/visitors/%7BvisitorId%7D";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    MeterRegistry registry;

    RawEventRows raw;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
        raw = new RawEventRows(jdbc);
    }

    @Test
    void erasesOnlyThatIdAndAnswers204AlsoOnRepeat(CapturedOutput output) throws Exception {
        UUID erased = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        raw.pageview(erased, Instant.now().minusSeconds(3600), "/");
        raw.goal(erased, Instant.now().minusSeconds(3000));
        raw.pageview(kept, Instant.now().minusSeconds(3600), "/");

        erase(erased.toString()).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(rows(erased)).isZero();
        assertThat(rows(kept)).isEqualTo(1);
        assertThat(output).contains("[webanalytics] visitor erased rows=2").doesNotContain(erased.toString());

        erase(erased.toString()).andExpect(status().isNoContent());
        erase(UUID.randomUUID().toString()).andExpect(status().isNoContent()); // nothing matched
    }

    @Test
    void aBatchAfterTheDeleteLeavesZeroRowsAndCountsErased() throws Exception {
        UUID visitor = UUID.randomUUID();
        erase(visitor.toString()).andExpect(status().isNoContent());
        double before = registry.get("webanalytics.events.rejected").tag("reason", "erased").counter().count();

        Map<String, Object> pageview = new LinkedHashMap<>(event("pageview", Instant.now()));
        pageview.put("visitorId", visitor.toString());
        Map<String, Object> goal = new LinkedHashMap<>(event("goal", Instant.now()));
        goal.put("visitorId", visitor.toString());
        mockMvc.perform(post(EVENTS).header("Authorization", "Bearer " + writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content(batch(List.of(pageview, goal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.rejected").value(2));

        assertThat(rows(visitor)).isZero();
        assertThat(registry.get("webanalytics.events.rejected").tag("reason", "erased").counter().count() - before)
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT events FROM webanalytics.daily_ingest_stats WHERE stage = 'rejected' "
                + "AND reason = 'erased'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void deletesInBatchesBeyondOneBatch() throws Exception {
        UUID visitor = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webanalytics.events (event_id, event_type, occurred_at, received_at, visitor_id,
                            pageview_id, seq, path, locale, landing, device_class, browser_family, os_family, source)
                        SELECT gen_random_uuid(), 'pageview', now() - interval '1 day', now() - interval '1 day',
                               CAST(:visitor AS uuid), gen_random_uuid(), 0, '/', 'de', false, 'desktop', 'firefox',
                               'macos', 'furchert-ch'
                        FROM generate_series(1, 5100)
                        """).param("visitor", visitor.toString()).update();
        erase(visitor.toString()).andExpect(status().isNoContent());
        assertThat(rows(visitor)).isZero();
    }

    @Test
    void theBarrierWaitsForAnInFlightBatchAndAnswers503WhenItCannotGetTheLock() throws Exception {
        UUID visitor = UUID.randomUUID();
        raw.pageview(visitor, Instant.now().minusSeconds(60), "/");
        try (Connection batch = dataSource.getConnection(); Statement s = batch.createStatement()) {
            batch.setAutoCommit(false);
            s.execute("SELECT pg_advisory_xact_lock_shared(hashtext('webanalytics-ingest'))");
            // Held for longer than lock_timeout 1 s: 503, retryable; the tombstone already keeps new inserts out.
            erase(visitor.toString()).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("internal"))
                    .andExpect(jsonPath("$.instance").value(TEMPLATE))
                    .andExpect(content().string(not(containsString(visitor.toString()))));

            // Released while the erasure waits: the erasure completes after the in-flight batch.
            CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(300);
                    batch.commit();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            erase(visitor.toString()).andExpect(status().isNoContent());
            release.get(5, TimeUnit.SECONDS);
        }
        assertThat(rows(visitor)).isZero();
    }

    @Test
    void aBadIdIs400WithoutEchoingIt() throws Exception {
        for (String bad : List.of("not-a-uuid", "3F0C2A1B-1111-4111-8111-111111111111", "3f0c2a1b-1111-1111-8111-111111111111")) {
            erase(bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_parameter"))
                    .andExpect(jsonPath("$.instance").value(TEMPLATE))
                    .andExpect(content().string(not(containsString(bad))));
        }
    }

    @Test
    void accessControlAndMethods() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(delete("/api/webanalytics/visitors/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.instance").value(TEMPLATE));
        String readToken = TestJwks.token(c -> c.claim("scope", List.of("analytics:read")));
        erase(id, readToken).andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString(id))));
        erase(id, TestJwks.token(c -> c.claim("scope", List.of("analytics:write")).claim("role", "ADMIN")))
                .andExpect(status().isForbidden());
        erase(id, TestJwks.token(c -> c.claim("scope", List.of("analytics:write")).subject("other-client")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/webanalytics/visitors/" + id).header("Authorization", "Bearer " + writeToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.instance").value(TEMPLATE));
    }

    private ResultActions erase(String id) throws Exception {
        return erase(id, writeToken());
    }

    private ResultActions erase(String id, String token) throws Exception {
        return mockMvc.perform(delete("/api/webanalytics/visitors/" + id).header("Authorization", "Bearer " + token));
    }

    private long rows(UUID visitor) {
        return jdbc.sql("SELECT count(*) FROM webanalytics.events WHERE visitor_id = :v").param("v", visitor)
                .query(Long.class).single();
    }
}
