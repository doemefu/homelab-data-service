package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One visit built by {@link VisitBuilder} (docs/100 §3.1, §7.5). Facts are derived on demand; a visit without a
 * pageview is an orphan, a visit with more than 100 pageview events or more than 500 events is excluded.
 */
public record Visit(
        UUID visitorId,
        Instant firstEventAt,
        RawEvent firstPageview,
        List<PageSlot> slots,
        List<RawEvent> clicks,
        int goals,
        int pageviewEvents,
        int events) {

    static final int MAX_PAGEVIEWS = 100;
    static final int MAX_EVENTS = 500;
    static final long ENGAGED_MS = 10_000;
    static final Set<String> OFFER_PATHS = Set.of("/automation", "/it");
    static final String CONTACT_PATH = "/contact";
    /** Click kinds that are a contact intent and a key event (docs/100 §3.1, rev 3.4: incl. {@code tel}). */
    static final Set<String> INTENT_CLICK_KINDS = Set.of("cta", "mailto", "tel");

    public boolean orphan() {
        return slots.isEmpty();
    }

    public boolean excluded() {
        return pageviewEvents > MAX_PAGEVIEWS || events > MAX_EVENTS;
    }

    /** The instant the visit is attributed by: its first pageview, or its first event for an orphan. */
    public Instant attributedAt() {
        return firstPageview != null ? firstPageview.occurredAt() : firstEventAt;
    }

    public String entryPath() {
        return slots.getFirst().path();
    }

    public String exitPath() {
        return slots.getLast().path();
    }

    public String entryLocale() {
        return slots.getFirst().locale();
    }

    public String deviceClass() {
        String device = firstPageview.deviceClass();
        return device == null ? "other" : device;
    }

    public long engagedMs() {
        return slots.stream().filter(PageSlot::hasEngagement).mapToLong(PageSlot::engagedMs).sum();
    }

    public Set<String> distinctPaths() {
        return slots.stream().map(PageSlot::path).collect(Collectors.toSet());
    }

    public boolean offer() {
        return slots.stream().anyMatch(s -> OFFER_PATHS.contains(s.path()));
    }

    public boolean contactIntent() {
        return clicks.stream().anyMatch(c -> INTENT_CLICK_KINDS.contains(c.clickKind()))
                || slots.stream().anyMatch(s -> CONTACT_PATH.equals(s.path()));
    }

    public boolean formStart() {
        return slots.stream().anyMatch(PageSlot::formStarted);
    }

    public boolean converting() {
        return goals > 0;
    }

    /** A {@code cta} click, a contact-intent event, a form start or a goal (docs/100 §8.3, rev 3.4). */
    public boolean keyEvent() {
        return contactIntent() || formStart() || converting();
    }

    /** ≥ 10 s engaged time, or ≥ 2 distinct pages, or a key event (docs/100 §8.3). */
    public boolean engaged() {
        return engagedMs() >= ENGAGED_MS || distinctPaths().size() >= 2 || keyEvent();
    }
}
