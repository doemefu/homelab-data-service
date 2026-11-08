package ch.furchert.homelab.data.webanalytics.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The WA-1 ingest counters (docs/100 §7.9). Every series is registered at startup with 0, so {@code increase()}
 * works from the first scrape; label values come only from {@link RejectReason} and the fixed {@code edgeDrops}
 * keys, never from request data.
 */
@Component
public class WebAnalyticsMetrics {

    /** The nine {@code edgeDrops} keys (docs/100 §6.2), in wire order. */
    public static final List<String> EDGE_REASONS = List.of("bot", "signed_in", "consent", "invalid", "path",
            "rate_limited", "mint_limited", "overflow", "expired");

    private final Counter accepted;
    private final Counter duplicates;
    private final Counter enquiries;
    private final Map<RejectReason, Counter> rejected = new EnumMap<>(RejectReason.class);
    private final Map<String, Counter> edgeDropped = new LinkedHashMap<>();

    public WebAnalyticsMetrics(MeterRegistry registry) {
        accepted = Counter.builder("webanalytics.events.accepted")
                .description("Analytics events stored (enquiries excluded)")
                .register(registry);
        duplicates = Counter.builder("webanalytics.events.duplicate")
                .description("Analytics events already stored (enquiries excluded)")
                .register(registry);
        enquiries = Counter.builder("webanalytics.enquiries")
                .description("Enquiry increments of daily_enquiries")
                .register(registry);
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, Counter.builder("webanalytics.events.rejected")
                    .description("Events rejected by the second-line validation")
                    .tag("reason", reason.label())
                    .register(registry));
        }
        for (String reason : EDGE_REASONS) {
            edgeDropped.put(reason, Counter.builder("webanalytics.edge.dropped")
                    .description("Events dropped by furchert-ch before forwarding, as reported in edgeDrops")
                    .tag("reason", reason)
                    .register(registry));
        }
    }

    void record(IngestOutcome outcome) {
        accepted.increment(outcome.accepted());
        duplicates.increment(outcome.duplicates());
        enquiries.increment(outcome.enquiries());
        outcome.rejected().forEach((reason, count) -> rejected.get(reason).increment(count));
        outcome.edgeDrops().forEach((reason, count) -> {
            Counter counter = edgeDropped.get(reason);
            if (counter != null) {
                counter.increment(count);
            }
        });
    }
}
