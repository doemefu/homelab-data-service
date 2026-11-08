package ch.furchert.homelab.data.support;

import ch.furchert.homelab.data.webanalytics.rollup.RawEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** Builders for rollup test events; local times are Europe/Zurich. */
public final class RollupEvents {

    public static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private RollupEvents() {
    }

    public static Instant zurich(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZURICH).toInstant();
    }

    public static RawEvent pageview(UUID visitor, UUID pageviewId, Instant at, String path, boolean landing,
                                    String referrer, String utmSource) {
        return new RawEvent(UUID.randomUUID(), RawEvent.PAGEVIEW, at, visitor, pageviewId, 0, path, "de", landing,
                referrer, utmSource, null, null, "desktop", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RawEvent pageview(UUID visitor, UUID pageviewId, Instant at, String path) {
        return pageview(visitor, pageviewId, at, path, false, null, null);
    }

    public static RawEvent pageviewIn(UUID visitor, UUID pageviewId, Instant at, String path, String locale,
                                      String device) {
        return new RawEvent(UUID.randomUUID(), RawEvent.PAGEVIEW, at, visitor, pageviewId, 0, path, locale, false,
                null, null, null, null, device, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static RawEvent engagement(UUID visitor, UUID pageviewId, Instant at, String path, int engagedMs,
                                      int scrollMax, boolean fits, int sectionMask, boolean formStarted) {
        return new RawEvent(UUID.randomUUID(), RawEvent.ENGAGEMENT, at, visitor, pageviewId, 1, path, "de", null, null,
                null, null, null, null, engagedMs, scrollMax, fits, sectionMask, formStarted, null, null, null, null, null, null, null);
    }

    public static RawEvent click(UUID visitor, UUID pageviewId, Instant at, String path, String kind, String target) {
        return new RawEvent(UUID.randomUUID(), RawEvent.CLICK, at, visitor, pageviewId, 2, path, "de", null, null, null,
                null, null, null, null, null, null, null, null, kind, target, "main", null, null, null, null);
    }

    public static RawEvent goal(UUID visitor, Instant at) {
        return new RawEvent(UUID.randomUUID(), RawEvent.GOAL, at, visitor, null, null, "/contact", "de", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
