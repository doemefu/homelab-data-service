package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.util.UUID;

/** One {@code webanalytics.events} row as the rollup needs it. */
public record RawEvent(
        UUID eventId,
        String type,
        Instant occurredAt,
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
        String deviceClass,
        Integer engagedMs,
        Integer scrollMax,
        Boolean fits,
        Integer sectionMask,
        Boolean formStarted,
        String clickKind,
        String clickTarget,
        String clickArea,
        String country,
        String browserFamily,
        String osFamily,
        String viewport) {

    public static final String PAGEVIEW = "pageview";
    public static final String ENGAGEMENT = "engagement";
    public static final String CLICK = "click";
    public static final String GOAL = "goal";

    public boolean isPageview() {
        return PAGEVIEW.equals(type);
    }

    public boolean isEngagement() {
        return ENGAGEMENT.equals(type);
    }

    public boolean isClick() {
        return CLICK.equals(type);
    }

    public boolean isGoal() {
        return GOAL.equals(type);
    }

    /** A landing pageview that carries a referrer or a UTM value starts a new visit (docs/100 §3.1). */
    public boolean startsVisitByAttribution() {
        return isPageview() && Boolean.TRUE.equals(landing)
                && (referrerDomain != null || utmSource != null || utmMedium != null || utmCampaign != null);
    }
}
