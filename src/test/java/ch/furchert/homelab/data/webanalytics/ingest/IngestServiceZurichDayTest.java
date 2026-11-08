package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EdgeDrops;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day boundaries (docs/100 §7.2: Zurich day of {@code occurredAt} for enquiries, of receipt for ingest stats) and
 * the commit/rollback hooks of {@link IngestService}, without a database.
 */
class IngestServiceZurichDayTest {

    private static final EdgeDrops ZERO = new EdgeDrops(0, 0, 0, 0, 0, 0, 0, 0, 0);

    private final WebAnalyticsRepository repository = mock(WebAnalyticsRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        when(repository.insertEvents(any(), anyString())).thenReturn(new int[0]);
        when(repository.existing(anyCollection(), anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void winterEnquiryLateInTheUtcDayCountsForTheNextZurichDay() {
        Instant now = Instant.parse("2026-11-08T23:40:00Z"); // 00:40 CET on 2026-11-09
        service(now, new EnquiryDeduplicator(Clock.fixed(now, ZoneOffset.UTC)))
                .ingest(batchOf(WebAnalyticsFixtures.event("enquiry", now)), "furchert-ch");
        assertThat(enquiryDays()).containsExactly(Map.entry(LocalDate.parse("2026-11-09"), 1L));
        verify(repository).incrementIngestStats(eq(LocalDate.parse("2026-11-09")), any());
    }

    @Test
    void summerEnquiryUsesCest() {
        Instant now = Instant.parse("2026-07-01T21:59:00Z"); // 23:59 CEST on 2026-07-01
        service(now, new EnquiryDeduplicator(Clock.fixed(now, ZoneOffset.UTC)))
                .ingest(batchOf(WebAnalyticsFixtures.event("enquiry", now)), "furchert-ch");
        assertThat(enquiryDays()).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), 1L));
    }

    @Test
    void dstEndNightStaysOnTheSameZurichDay() {
        Instant now = Instant.parse("2026-10-25T01:30:00Z"); // 02:30 CET, after the switch back on 2026-10-25
        service(now, new EnquiryDeduplicator(Clock.fixed(now, ZoneOffset.UTC)))
                .ingest(batchOf(WebAnalyticsFixtures.event("enquiry", now)), "furchert-ch");
        assertThat(enquiryDays()).containsExactly(Map.entry(LocalDate.parse("2026-10-25"), 1L));
    }

    @Test
    void metricsAreRecordedOnlyAfterCommit() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        service(now, new EnquiryDeduplicator(Clock.fixed(now, ZoneOffset.UTC)))
                .ingest(batchOf(WebAnalyticsFixtures.event("enquiry", now)), "furchert-ch");
        assertThat(registry.get("webanalytics.enquiries").counter().count()).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.get("webanalytics.enquiries").counter().count()).isEqualTo(1);
    }

    @Test
    void rollbackReleasesTheEnquiryReservation() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        EnquiryDeduplicator dedupe = new EnquiryDeduplicator(Clock.fixed(now, ZoneOffset.UTC));
        Map<String, Object> enquiry = WebAnalyticsFixtures.event("enquiry", now);
        service(now, dedupe).ingest(batchOf(enquiry), "furchert-ch");
        UUID id = UUID.fromString((String) enquiry.get("eventId"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(dedupe.reserve(id)).isTrue();
        assertThat(registry.get("webanalytics.enquiries").counter().count()).isZero();
    }

    private IngestService service(Instant now, EnquiryDeduplicator dedupe) {
        WebAnalyticsProperties properties = new WebAnalyticsProperties(
                new WebAnalyticsProperties.Api(List.of("furchert-ch")), ZoneId.of("Europe/Zurich"));
        return new IngestService(new EventValidator(), repository, dedupe, new WebAnalyticsMetrics(registry),
                Clock.fixed(now, ZoneOffset.UTC), properties);
    }

    private static IngestRequest batchOf(Map<String, Object> event) {
        return new IngestRequest(List.of(WebAnalyticsFixtures.toDto(event)), ZERO);
    }

    @SuppressWarnings("unchecked")
    private SortedMap<LocalDate, Long> enquiryDays() {
        ArgumentCaptor<SortedMap<LocalDate, Long>> captor = ArgumentCaptor.forClass(SortedMap.class);
        verify(repository).incrementEnquiries(captor.capture());
        return captor.getValue();
    }

}
