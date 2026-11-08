package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.webanalytics.ingest.WebAnalyticsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/100 §6.2: the 2 s statement timeout is in force inside the ingest transaction only (review N3). */
class WebAnalyticsStatementTimeoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    WebAnalyticsRepository repository;

    @Test
    void statementTimeoutIsTwoSecondsInsideTheIngestTransaction() throws Exception {
        AtomicReference<String> inside = new AtomicReference<>();
        doAnswer(invocation -> {
            // Runs on the transaction's connection, after the batch inserts.
            inside.set(jdbc.sql("SHOW statement_timeout").query(String.class).single());
            return invocation.callRealMethod();
        }).when(repository).incrementIngestStats(any(), any());

        mockMvc.perform(post(WebAnalyticsFixtures.EVENTS)
                        .header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WebAnalyticsFixtures.batch(List.of(WebAnalyticsFixtures.event("click", Instant.now())))))
                .andExpect(status().isOk());

        assertThat(inside.get()).isEqualTo("2s");
        // SET LOCAL ends with the transaction: a pooled connection keeps the server default.
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("0");
    }
}
