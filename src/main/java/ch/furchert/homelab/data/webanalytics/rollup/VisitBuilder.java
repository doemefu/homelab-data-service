package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the visits of one visitor from its events (docs/100 §3.1, §7.5). Only pageviews, goals and events whose
 * {@code pageviewId} is not a loaded pageview ("anchors") open visits and measure gaps; engagement and click events
 * of a known pageview join that pageview's visit regardless of the gap, so a late {@code pagehide} snapshot never
 * opens an orphan visit. A new visit starts at a gap of more than 30 min between anchors, at a landing pageview with a
 * referrer or UTM value, and when the visit would span more than 6 h (continuous activity beyond 6 h is cut, an
 * accepted data-quality limit). Same-path pageviews at most 30 s apart are merged into one counted pageview.
 */
public final class VisitBuilder {

    static final Duration GAP = Duration.ofMinutes(30);
    static final Duration MAX_SPAN = Duration.ofHours(6);
    static final Duration MERGE_WINDOW = Duration.ofSeconds(30);

    private static final Comparator<RawEvent> ORDER = Comparator.comparing(RawEvent::occurredAt)
            .thenComparing(RawEvent::eventId);

    private VisitBuilder() {
    }

    /** @param events the events of one visitor, in any order */
    public static List<Visit> build(List<RawEvent> events) {
        List<RawEvent> sorted = new ArrayList<>(events);
        sorted.sort(ORDER);
        Set<UUID> pageviewIds = new HashSet<>();
        for (RawEvent e : sorted) {
            if (e.isPageview()) {
                pageviewIds.add(e.pageviewId());
            }
        }

        List<List<RawEvent>> drafts = new ArrayList<>();
        Map<UUID, List<RawEvent>> draftOfPageview = new HashMap<>();
        List<RawEvent> attached = new ArrayList<>();
        List<RawEvent> current = null;
        Instant visitStart = null;
        Instant lastAnchor = null;
        for (RawEvent e : sorted) {
            boolean anchor = e.isPageview() || e.isGoal() || e.pageviewId() == null
                    || !pageviewIds.contains(e.pageviewId());
            if (!anchor) {
                attached.add(e);
                continue;
            }
            boolean split = current == null
                    || Duration.between(lastAnchor, e.occurredAt()).compareTo(GAP) > 0
                    || Duration.between(visitStart, e.occurredAt()).compareTo(MAX_SPAN) > 0
                    || e.startsVisitByAttribution();
            if (split) {
                current = new ArrayList<>();
                drafts.add(current);
                visitStart = e.occurredAt();
            }
            current.add(e);
            lastAnchor = e.occurredAt();
            if (e.isPageview()) {
                draftOfPageview.put(e.pageviewId(), current);
            }
        }
        for (RawEvent e : attached) {
            draftOfPageview.get(e.pageviewId()).add(e);
        }
        return drafts.stream().map(VisitBuilder::toVisit).toList();
    }

    private static Visit toVisit(List<RawEvent> draft) {
        draft.sort(ORDER);
        Map<UUID, List<RawEvent>> snapshots = new HashMap<>();
        List<RawEvent> clicks = new ArrayList<>();
        int goals = 0;
        int pageviewEvents = 0;
        RawEvent firstPageview = null;
        List<List<RawEvent>> slotPageviews = new ArrayList<>();
        RawEvent previousPageview = null;
        for (RawEvent e : draft) {
            if (e.isPageview()) {
                pageviewEvents++;
                if (firstPageview == null) {
                    firstPageview = e;
                }
                boolean merge = previousPageview != null
                        && previousPageview.path().equals(e.path())
                        && Duration.between(previousPageview.occurredAt(), e.occurredAt()).compareTo(MERGE_WINDOW) <= 0;
                if (merge) {
                    slotPageviews.getLast().add(e);
                } else {
                    slotPageviews.add(new ArrayList<>(List.of(e)));
                }
                previousPageview = e;
            } else if (e.isEngagement()) {
                snapshots.computeIfAbsent(e.pageviewId(), k -> new ArrayList<>()).add(e);
            } else if (e.isClick()) {
                clicks.add(e);
            } else if (e.isGoal()) {
                goals++;
            }
        }
        List<PageSlot> slots = slotPageviews.stream().map(pvs -> toSlot(pvs, snapshots)).toList();
        return new Visit(draft.getFirst().visitorId(), draft.getFirst().occurredAt(), firstPageview, slots,
                List.copyOf(clicks), goals, pageviewEvents, draft.size());
    }

    private static PageSlot toSlot(List<RawEvent> pageviews, Map<UUID, List<RawEvent>> snapshots) {
        Long engaged = null;
        Integer scroll = null;
        int mask = 0;
        boolean anySnapshot = false;
        boolean allFit = true;
        boolean formStarted = false;
        Map<UUID, Integer> maxEngagedPerPageview = new LinkedHashMap<>();
        for (RawEvent pv : pageviews) {
            for (RawEvent s : snapshots.getOrDefault(pv.pageviewId(), List.of())) {
                anySnapshot = true;
                maxEngagedPerPageview.merge(pv.pageviewId(), s.engagedMs(), Math::max);
                scroll = scroll == null ? s.scrollMax() : Math.max(scroll, s.scrollMax());
                mask |= s.sectionMask();
                allFit &= Boolean.TRUE.equals(s.fits());
                formStarted |= Boolean.TRUE.equals(s.formStarted());
            }
        }
        if (anySnapshot) {
            engaged = maxEngagedPerPageview.values().stream().mapToLong(Integer::longValue).sum();
        }
        List<UUID> ids = pageviews.stream().map(RawEvent::pageviewId).toList();
        return new PageSlot(pageviews.getFirst().path(), pageviews.getFirst().locale(), ids, engaged, scroll, mask,
                anySnapshot && allFit, formStarted);
    }
}
