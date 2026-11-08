package ch.furchert.homelab.data.webanalytics.rollup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Visit-scoped journey facts of WA-3b (docs/100 §7.8, §8.5, rev 3.5): funnel steps, page-group path key, transitions
 * and what was seen before the first contact intent. Snapshot-derived facts (section {@code preise} reached, form
 * start) are ordered by their pageview's start ({@link PageSlot#startedAt()}); clicks, pageviews and goals by their own
 * time. Funnels match greedily at the earliest qualifying event; step k counts only if steps 1..k occurred in order.
 */
public final class Journeys {

    public static final String OFFER = "offer";
    public static final String CONTACT_FORM = "contact_form";
    public static final List<String> OFFER_STEPS = List.of("visit", "offer_visit", "reached_prices", "contact_intent",
            "form_start", "contact_submit");
    public static final List<String> CONTACT_FORM_STEPS = List.of("contact_pageview", "form_start", "contact_submit");
    public static final String ENTRANCE = "(entrance)";
    public static final String EXIT = "(exit)";
    static final int PRICES_BIT = 3; // §3.5: bit 3 = section "preise"
    static final int MAX_GROUP_STEPS = 4;

    /** What a contact-intent visit had seen before its first intent (docs/100 §7.8). */
    public enum Seen { ABOUT, PROJECTS, PRICES, NONE }

    private Journeys() {
    }

    /** Steps of F1 {@code offer} reached in order (1..6). */
    public static int offerSteps(Visit v) {
        Optional<Instant> offer = v.slots().stream().filter(s -> Visit.OFFER_PATHS.contains(s.path()))
                .map(PageSlot::startedAt).min(Instant::compareTo);
        if (offer.isEmpty()) {
            return 1;
        }
        Optional<Instant> prices = v.slots().stream()
                .filter(s -> reachedPrices(s) && !s.startedAt().isBefore(offer.get()))
                .map(PageSlot::startedAt).min(Instant::compareTo);
        if (prices.isEmpty()) {
            return 2;
        }
        Optional<Instant> intent = firstIntent(v, prices.get());
        if (intent.isEmpty()) {
            return 3;
        }
        Optional<Instant> formStart = firstFormStart(v, intent.get());
        if (formStart.isEmpty()) {
            return 4;
        }
        return firstGoal(v, formStart.get()).isPresent() ? 6 : 5;
    }

    /** Steps of F2 {@code contact_form} reached in order (0..3). */
    public static int contactFormSteps(Visit v) {
        Optional<Instant> contact = v.slots().stream().filter(s -> Visit.CONTACT_PATH.equals(s.path()))
                .map(PageSlot::startedAt).min(Instant::compareTo);
        if (contact.isEmpty()) {
            return 0;
        }
        Optional<Instant> formStart = firstFormStart(v, contact.get());
        if (formStart.isEmpty()) {
            return 1;
        }
        return firstGoal(v, formStart.get()).isPresent() ? 3 : 2;
    }

    /** Page group of a locale-free path (docs/100 §7.8, rev 3.5: sixth group {@code other}). */
    public static String group(String path) {
        if ("/".equals(path)) {
            return "home";
        }
        if (Visit.OFFER_PATHS.contains(path)) {
            return "offer";
        }
        if ("/projects".equals(path) || path.startsWith("/projects/") || "/about".equals(path)) {
            return "proof";
        }
        if ("/rowing".equals(path)) {
            return "personal";
        }
        if (Visit.CONTACT_PATH.equals(path)) {
            return "contact";
        }
        return "other";
    }

    /**
     * Group path key: groups with consecutive repeats collapsed, the first four joined by {@code " > "}, then
     * {@code " > …"} if more followed, always ending {@code " > (exit)"}.
     */
    public static String groupPathKey(Visit v) {
        List<String> groups = collapse(v.slots().stream().map(s -> group(s.path())).toList());
        List<String> parts = new ArrayList<>(groups.subList(0, Math.min(MAX_GROUP_STEPS, groups.size())));
        if (groups.size() > MAX_GROUP_STEPS) {
            parts.add("…");
        }
        parts.add(EXIT);
        return String.join(" > ", parts);
    }

    /** Consecutive path pairs from {@code (entrance)} to {@code (exit)}, identical consecutive paths collapsed. */
    public static List<String[]> transitions(Visit v) {
        List<String> path = collapse(v.slots().stream().map(PageSlot::path).toList());
        List<String> tokens = Stream.concat(Stream.concat(Stream.of(ENTRANCE), path.stream()), Stream.of(EXIT))
                .toList();
        List<String[]> pairs = new ArrayList<>();
        for (int i = 1; i < tokens.size(); i++) {
            pairs.add(new String[]{tokens.get(i - 1), tokens.get(i)});
        }
        return pairs;
    }

    /** For a contact-intent visit: what it had seen before its first intent; empty for a visit without intent. */
    public static Set<Seen> seenBeforeIntent(Visit v) {
        Optional<Instant> intent = firstIntent(v, Instant.MIN);
        if (intent.isEmpty()) {
            return Set.of();
        }
        Instant ti = intent.get();
        EnumSet<Seen> seen = EnumSet.noneOf(Seen.class);
        for (PageSlot s : v.slots()) {
            if (!s.startedAt().isBefore(ti)) {
                continue;
            }
            if ("/about".equals(s.path())) {
                seen.add(Seen.ABOUT);
            }
            if ("/projects".equals(s.path()) || s.path().startsWith("/projects/")) {
                seen.add(Seen.PROJECTS);
            }
            if (reachedPrices(s)) {
                seen.add(Seen.PRICES);
            }
        }
        return seen.isEmpty() ? EnumSet.of(Seen.NONE) : seen;
    }

    private static boolean reachedPrices(PageSlot s) {
        return "/automation".equals(s.path()) && (s.sectionMask() & (1 << PRICES_BIT)) != 0;
    }

    private static Optional<Instant> firstIntent(Visit v, Instant notBefore) {
        Stream<Instant> clicks = v.clicks().stream()
                .filter(c -> Visit.INTENT_CLICK_KINDS.contains(c.clickKind()))
                .map(RawEvent::occurredAt);
        Stream<Instant> contact = v.slots().stream().filter(s -> Visit.CONTACT_PATH.equals(s.path()))
                .map(PageSlot::startedAt);
        return Stream.concat(clicks, contact).filter(t -> !t.isBefore(notBefore)).min(Instant::compareTo);
    }

    private static Optional<Instant> firstFormStart(Visit v, Instant notBefore) {
        return v.slots().stream().filter(s -> s.formStarted() && !s.startedAt().isBefore(notBefore))
                .map(PageSlot::startedAt).min(Instant::compareTo);
    }

    private static Optional<Instant> firstGoal(Visit v, Instant notBefore) {
        return v.goalTimes().stream().filter(t -> !t.isBefore(notBefore)).min(Instant::compareTo);
    }

    private static List<String> collapse(List<String> values) {
        List<String> collapsed = new ArrayList<>();
        for (String value : values) {
            if (collapsed.isEmpty() || !collapsed.getLast().equals(value)) {
                collapsed.add(value);
            }
        }
        return collapsed;
    }
}
