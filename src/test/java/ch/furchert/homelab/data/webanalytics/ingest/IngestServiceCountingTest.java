package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EdgeDrops;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import ch.furchert.homelab.data.webanalytics.ingest.WebAnalyticsRepository.StatsKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Counting rules of {@link IngestService} without a database (docs/100 §6.2, §7.2; review B1, N1, N3). */
class IngestServiceCountingTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final EdgeDrops ZERO = new EdgeDrops(0, 0, 0, 0, 0, 0, 0, 0, 0);

    private final WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
    private IngestService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.existing(anyCollection(), anyCollection())).thenReturn(List.of());
        WebAnalyticsProperties properties = new WebAnalyticsProperties(
                new WebAnalyticsProperties.Api(List.of("furchert-ch"), List.of("furchert-ch")), ZoneId.of("Europe/Zurich"));
        service = new IngestService(new EventValidator(), repository,
                new EnquiryDeduplicator(Clock.fixed(NOW, ZoneOffset.UTC)), new WebAnalyticsMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC), properties, new IngestActivity(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void insertConflictFromAConcurrentBatchCountsAsDuplicate() {
        // Both events pass the pre-query; the second loses the ON CONFLICT race (update count 0).
        when(repository.insertEvents(any(), anyString())).thenReturn(new int[]{1, 0});

        IngestOutcome outcome = service.ingest(batch(WebAnalyticsFixtures.event("click", NOW),
                WebAnalyticsFixtures.event("click", NOW)), "furchert-ch");

        assertThat(outcome.accepted()).isEqualTo(1);
        assertThat(outcome.duplicates()).isEqualTo(1);
        assertThat(stats()).containsEntry(new StatsKey("accepted", ""), 1L)
                .containsEntry(new StatsKey("duplicate", ""), 1L);
    }

    @Test
    void invalidEnquiryIsRejectedButNeverCountedInIngestStats() {
        when(repository.insertEvents(any(), anyString())).thenReturn(new int[0]);

        IngestOutcome outcome = service.ingest(batch(
                WebAnalyticsFixtures.event("enquiry", NOW, Map.of("visitorId", UUID.randomUUID().toString())),
                WebAnalyticsFixtures.event("click", NOW, Map.of("clickTarget", "bad_id"))), "furchert-ch");

        // Response and metric count both rejects (Q4) ...
        assertThat(outcome.rejectedTotal()).isEqualTo(2);
        assertThat(outcome.rejected()).containsEntry(RejectReason.SHAPE, 2);
        // ... daily_ingest_stats only the click (docs/100 §7.2).
        assertThat(stats()).containsEntry(new StatsKey("rejected", "shape"), 1L);
    }

    @Test
    void statementTimeoutIsAppliedBeforeAnyBatchStatement() {
        when(repository.insertEvents(any(), anyString())).thenReturn(new int[]{1});

        service.ingest(batch(WebAnalyticsFixtures.event("click", NOW)), "furchert-ch");

        InOrder order = inOrder(repository);
        order.verify(repository).applyStatementTimeout();
        order.verify(repository).existing(anyCollection(), anyCollection());
        order.verify(repository).insertEvents(any(), anyString());
        order.verify(repository).incrementIngestStats(any(), any());
        order.verify(repository).incrementEnquiries(any());
    }

    @SafeVarargs
    private static IngestRequest batch(Map<String, Object>... events) {
        return new IngestRequest(java.util.Arrays.stream(events).map(WebAnalyticsFixtures::toDto).toList(), ZERO);
    }

    @SuppressWarnings("unchecked")
    private SortedMap<StatsKey, Long> stats() {
        ArgumentCaptor<SortedMap<StatsKey, Long>> captor = ArgumentCaptor.forClass(SortedMap.class);
        verify(repository).incrementIngestStats(any(), captor.capture());
        return captor.getValue();
    }
}
