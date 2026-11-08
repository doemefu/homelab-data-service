package ch.furchert.homelab.data.webanalytics.ingest;

import java.util.Locale;

/**
 * Per-event reject reasons of the second-line validation (docs/100 §7.9), the bounded label set of
 * {@code webanalytics_events_rejected_total{reason}} and the {@code rejected} reasons of {@code daily_ingest_stats}.
 */
public enum RejectReason {
    /** Missing/extra field for the type, malformed id, or a malformed free-form value (referrer, UTM, click target). */
    SHAPE,
    /** A value outside a closed vocabulary (type, locale, families, viewport, click kind/area, goal, country). */
    ENUM,
    /** A number outside its range ({@code seq}, {@code engagedMs}, {@code scrollMax}, {@code sectionMask}). */
    RANGE,
    /** An unparsable instant, or {@code receivedAt}/{@code occurredAt} outside the accepted window. */
    TIME,
    /** {@code path} or a {@code nav} target that is not a locale-free canonical path. */
    PATH,
    /** Batch-context rejects: a {@code (pageviewId, seq)} already taken by another {@code eventId}. */
    OTHER;

    /** The lowercase label used in metrics and {@code daily_ingest_stats}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
