package ch.furchert.homelab.data.webanalytics.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire shapes of {@code POST /api/webanalytics/events} (docs/100 §6.2). */
public final class IngestDtos {

    private IngestDtos() {
    }

    /** The batch envelope; both members must be present (an explicit {@code null} is a 400). */
    public record IngestRequest(List<EventDto> events, EdgeDrops edgeDrops) {
    }

    /**
     * One forwarded event with exactly the 28 §6.2 fields, all present (null when absent). Ids and instants are
     * strings on purpose: a malformed value rejects this event only, while a wrong JSON type fails the batch.
     */
    public record EventDto(
            String eventId,
            String type,
            String occurredAt,
            String receivedAt,
            String visitorId,
            String pageviewId,
            Integer seq,
            String path,
            String locale,
            Boolean landing,
            String referrerDomain,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String country,
            String deviceClass,
            String browserFamily,
            String osFamily,
            String viewport,
            Integer engagedMs,
            Integer scrollMax,
            Boolean fits,
            Integer sectionMask,
            Boolean formStarted,
            String clickKind,
            String clickTarget,
            String clickArea,
            String goal) {
    }

    /** furchert-ch's per-reason drop counts since the previous batch; the nine keys are fixed (docs/100 §6.2). */
    public record EdgeDrops(
            Integer bot,
            @JsonProperty("signed_in") Integer signedIn,
            Integer consent,
            Integer invalid,
            Integer path,
            @JsonProperty("rate_limited") Integer rateLimited,
            @JsonProperty("mint_limited") Integer mintLimited,
            Integer overflow,
            Integer expired) {

        /** The counts keyed by their wire name, in a fixed order; values may be null until validated. */
        public Map<String, Integer> byReason() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("bot", bot);
            counts.put("signed_in", signedIn);
            counts.put("consent", consent);
            counts.put("invalid", invalid);
            counts.put("path", path);
            counts.put("rate_limited", rateLimited);
            counts.put("mint_limited", mintLimited);
            counts.put("overflow", overflow);
            counts.put("expired", expired);
            return counts;
        }
    }

    /** Response 200 (docs/100 §6.2). Enquiries appear in none of the counters; {@code capped} is false. */
    public record IngestResponse(int accepted, int duplicates, int rejected, boolean capped) {
    }

    /**
     * Response 429 of a capped batch (docs/100 §6.3): all counters 0, {@code capped} true, {@code capScope}
     * {@code hour} or {@code day}, {@code retryAt} ISO-8601 UTC in whole seconds.
     */
    public record CappedResponse(int accepted, int duplicates, int rejected, boolean capped, String capScope,
                                 String retryAt) {
    }
}
