package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EventDto;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import ch.furchert.homelab.data.webanalytics.ingest.WebAnalyticsRepository.StatsKey;
import ch.furchert.homelab.data.webanalytics.ingest.WebAnalyticsRepository.StoredKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stores one validated batch in one transaction (docs/100 §6.2, §7.2). Analytics events are deduplicated on
 * {@code eventId}; a {@code (pageviewId, seq)} already taken by another id is rejected ({@code other}). Enquiries
 * only increment {@code daily_enquiries} (Zurich day of {@code occurredAt}) behind the in-memory 2 h dedupe and
 * never appear in {@code accepted}/{@code duplicates}; an invalid enquiry counts as rejected in the response and
 * the metric, but never in {@code daily_ingest_stats}. Metrics are recorded after commit and enquiry reservations are released
 * on rollback, so a retried 500 neither double-counts nor loses anything.
 */
@Service
public class IngestService {

    static final String STAGE_ACCEPTED = "accepted";
    static final String STAGE_DUPLICATE = "duplicate";
    static final String STAGE_REJECTED = "rejected";
    static final String STAGE_EDGE = "edge";

    private final EventValidator validator;
    private final WebAnalyticsRepository repository;
    private final EnquiryDeduplicator enquiries;
    private final WebAnalyticsMetrics metrics;
    private final Clock clock;
    private final ZoneId zone;
    private final IngestActivity activity;

    public IngestService(EventValidator validator, WebAnalyticsRepository repository, EnquiryDeduplicator enquiries,
                         WebAnalyticsMetrics metrics, Clock clock, WebAnalyticsProperties properties,
                         IngestActivity activity) {
        this.validator = validator;
        this.repository = repository;
        this.enquiries = enquiries;
        this.metrics = metrics;
        this.clock = clock;
        this.zone = properties.timezone();
        this.activity = activity;
    }

    private record PageviewSeq(UUID pageviewId, int seq) {
    }

    /** @param source the writing client (the token's {@code sub}) */
    @Transactional
    public IngestOutcome ingest(IngestRequest request, String source) {
        List<UUID> reservedEnquiries = new ArrayList<>();
        // Response + metric count every reject (enquiries included); daily_ingest_stats never counts enquiries.
        Map<RejectReason, Integer> rejected = new EnumMap<>(RejectReason.class);
        Map<RejectReason, Integer> statsRejected = new EnumMap<>(RejectReason.class);
        Map<String, Integer> edgeDrops = new LinkedHashMap<>(request.edgeDrops().byReason());
        int[] counters = new int[2]; // accepted, duplicates
        List<ValidEvent> stored = new ArrayList<>();
        registerCompletion(reservedEnquiries, rejected, edgeDrops, counters, stored);

        repository.applyStatementTimeout();
        Instant now = clock.instant();
        SortedMap<LocalDate, Long> enquiryDays = new TreeMap<>();
        List<ValidEvent> candidates = new ArrayList<>();
        Set<UUID> batchIds = new HashSet<>();
        Map<PageviewSeq, UUID> batchKeys = new HashMap<>();

        for (EventDto dto : request.events()) {
            EventValidator.Result result = validator.validate(dto, now);
            if (!result.isValid()) {
                rejected.merge(result.reason(), 1, Integer::sum);
                if (!EventValidator.ENQUIRY.equals(dto.type())) {
                    statsRejected.merge(result.reason(), 1, Integer::sum); // §7.2: enquiries are never counted there
                }
                continue;
            }
            ValidEvent event = result.event();
            if (event.isEnquiry()) {
                if (enquiries.reserve(event.eventId())) {
                    reservedEnquiries.add(event.eventId());
                    enquiryDays.merge(LocalDate.ofInstant(event.occurredAt(), zone), 1L, Long::sum);
                }
                continue; // a duplicate enquiry within 2 h is ignored silently
            }
            if (batchIds.contains(event.eventId())) {
                counters[1]++;
                continue;
            }
            PageviewSeq key = keyOf(event);
            if (key != null && batchKeys.containsKey(key)) {
                rejected.merge(RejectReason.OTHER, 1, Integer::sum);
                statsRejected.merge(RejectReason.OTHER, 1, Integer::sum);
                continue;
            }
            batchIds.add(event.eventId());
            if (key != null) {
                batchKeys.put(key, event.eventId());
            }
            candidates.add(event);
        }

        List<ValidEvent> toInsert = classifyAgainstStored(candidates, batchIds, batchKeys, rejected, statsRejected,
                counters);
        int[] insertCounts = repository.insertEvents(toInsert, source);
        for (int i = 0; i < insertCounts.length; i++) {
            if (insertCounts[i] == 0) {
                counters[1]++; // lost a race against a concurrent batch
            } else {
                counters[0]++;
                stored.add(toInsert.get(i));
            }
        }

        // Upserts last and in key order, so concurrent batches lock the hot rows in the same order.
        repository.incrementIngestStats(LocalDate.ofInstant(now, zone),
                statsCounts(counters[0], counters[1], statsRejected, edgeDrops));
        repository.incrementEnquiries(enquiryDays);
        return new IngestOutcome(counters[0], counters[1], reservedEnquiries.size(), Map.copyOf(rejected),
                Map.copyOf(edgeDrops));
    }

    /** Drops stored duplicates and rejects stored-key reuse; returns the rest sorted by event id. */
    private List<ValidEvent> classifyAgainstStored(List<ValidEvent> candidates, Set<UUID> batchIds,
                                                   Map<PageviewSeq, UUID> batchKeys,
                                                   Map<RejectReason, Integer> rejected,
                                                   Map<RejectReason, Integer> statsRejected, int[] counters) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Object[]> pairs = batchKeys.keySet().stream()
                .map(key -> new Object[]{key.pageviewId(), key.seq()})
                .toList();
        Set<UUID> storedIds = new HashSet<>();
        Map<PageviewSeq, UUID> storedKeys = new HashMap<>();
        for (StoredKey row : repository.existing(batchIds, pairs)) {
            storedIds.add(row.eventId());
            if (row.pageviewId() != null && row.seq() != null) {
                storedKeys.put(new PageviewSeq(row.pageviewId(), row.seq()), row.eventId());
            }
        }
        List<ValidEvent> toInsert = new ArrayList<>();
        for (ValidEvent event : candidates) {
            PageviewSeq key = keyOf(event);
            if (storedIds.contains(event.eventId())) {
                counters[1]++;
            } else if (key != null && storedKeys.containsKey(key)) {
                rejected.merge(RejectReason.OTHER, 1, Integer::sum);
                statsRejected.merge(RejectReason.OTHER, 1, Integer::sum);
            } else {
                toInsert.add(event);
            }
        }
        toInsert.sort(Comparator.comparing(ValidEvent::eventId));
        return toInsert;
    }

    private static PageviewSeq keyOf(ValidEvent event) {
        return event.pageviewId() == null || event.seq() == null
                ? null : new PageviewSeq(event.pageviewId(), event.seq());
    }

    private static SortedMap<StatsKey, Long> statsCounts(int accepted, int duplicates,
                                                          Map<RejectReason, Integer> rejected,
                                                          Map<String, Integer> edgeDrops) {
        SortedMap<StatsKey, Long> counts = new TreeMap<>();
        counts.put(new StatsKey(STAGE_ACCEPTED, ""), (long) accepted);
        counts.put(new StatsKey(STAGE_DUPLICATE, ""), (long) duplicates);
        rejected.forEach((reason, count) -> counts.put(new StatsKey(STAGE_REJECTED, reason.label()), (long) count));
        edgeDrops.forEach((reason, count) -> counts.put(new StatsKey(STAGE_EDGE, reason), (long) count));
        return counts;
    }

    /**
     * After commit: record the metrics from the final counts. After rollback: release the enquiry reservations.
     * The arguments are filled while the batch is processed.
     */
    private void registerCompletion(List<UUID> reservedEnquiries, Map<RejectReason, Integer> rejected,
                                    Map<String, Integer> edgeDrops, int[] counters, List<ValidEvent> stored) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.record(new IngestOutcome(counters[0], counters[1], reservedEnquiries.size(),
                        Map.copyOf(rejected), Map.copyOf(edgeDrops)));
                activity.recordStored(
                        stored.stream().filter(e -> !EventValidator.GOAL.equals(e.type()))
                                .map(ValidEvent::receivedAt).toList(),
                        stored.stream().map(ValidEvent::receivedAt).max(Comparator.naturalOrder()).orElse(null));
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    reservedEnquiries.forEach(enquiries::release);
                }
            }
        });
    }
}
