package ch.furchert.homelab.data.webanalytics.ingest;

import java.time.Instant;
import java.util.UUID;

/** An event that passed {@link EventValidator}: ids and instants parsed, every other value as received. */
public record ValidEvent(
        UUID eventId,
        String type,
        Instant occurredAt,
        Instant receivedAt,
        UUID visitorId,
        UUID pageviewId,
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

    public boolean isEnquiry() {
        return EventValidator.ENQUIRY.equals(type);
    }
}
