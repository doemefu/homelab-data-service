package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EventDto;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Second-line, per-event validation of forwarded events (docs/100 §6.2, C13), independent of furchert-ch's first
 * line. Rules run in a fixed order and the first failure decides the reason; the rule set is a strict superset of
 * the {@code webanalytics.events} CHECKs (V6). Never throws: regexes run before every parse. Instants are checked
 * against the caller's {@code now}.
 */
@Component
public class EventValidator {

    static final String PAGEVIEW = "pageview";
    static final String ENGAGEMENT = "engagement";
    static final String CLICK = "click";
    static final String GOAL = "goal";
    static final String ENQUIRY = "enquiry";

    static final Set<String> TYPES = Set.of(PAGEVIEW, ENGAGEMENT, CLICK, GOAL, ENQUIRY);
    static final Set<String> LOCALES = Set.of("de", "en");
    static final Set<String> DEVICE_CLASSES = Set.of("mobile", "tablet", "desktop", "other");
    static final Set<String> BROWSER_FAMILIES = Set.of("edge", "firefox", "chrome", "safari", "other");
    static final Set<String> OS_FAMILIES = Set.of("ios", "android", "windows", "macos", "linux", "other");
    static final Set<String> VIEWPORTS = Set.of("<480", "480-767", "768-1023", "1024-1439", ">=1440");
    static final Set<String> CLICK_KINDS = Set.of("cta", "link", "faq", "lang", "nav", "outbound", "mailto", "tel");
    static final Set<String> TARGETLESS_CLICK_KINDS = Set.of("mailto", "tel");
    static final Set<String> CLICK_AREAS = Set.of("header", "footer", "main");
    static final Set<String> GOALS = Set.of("contact_submit");
    static final Set<Integer> SCROLL_STEPS = Set.of(0, 25, 50, 75, 90, 100);
    static final Set<String> RESERVED_COUNTRIES = Set.of("XX", "T1");

    static final int MAX_PATH_LENGTH = 200;
    static final int MAX_ENGAGED_MS = 1_800_000;
    static final int MAX_SEQ = 99;
    static final int MAX_SECTION_MASK = 255;
    static final String OTHER_NAV_TARGET = "(other)";

    static final Duration RECEIVED_PAST = Duration.ofHours(2);
    static final Duration RECEIVED_FUTURE = Duration.ofMinutes(1);
    static final Duration MAX_SKEW = Duration.ofMinutes(15);

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    /** Locale-free canonical path (docs/100 §6.2); identical to the V6 {@code events_path} CHECK. */
    static final Pattern PATH = Pattern.compile("^/([a-z0-9][a-z0-9-]{0,63}(/[a-z0-9][a-z0-9-]{0,63}){0,2})?$");
    private static final Pattern HOST = Pattern.compile("^[a-z0-9.-]{1,253}$");
    private static final Pattern UTM = Pattern.compile("^[a-z0-9._+-]{1,64}$");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{6}");
    private static final Pattern TRACK_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    /** Shape of an ISO-8601 instant, checked before {@link Instant#parse} so the parser never sees free text. */
    private static final Pattern INSTANT = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$");

    /** Either {@code event} or {@code reason} is set. */
    public record Result(ValidEvent event, RejectReason reason) {

        static Result rejected(RejectReason reason) {
            return new Result(null, reason);
        }

        public boolean isValid() {
            return event != null;
        }
    }

    public Result validate(EventDto e, Instant now) {
        // 1 shape: the idempotency key
        if (!isUuidV4(e.eventId())) {
            return Result.rejected(RejectReason.SHAPE);
        }
        // 2 enum: type
        if (e.type() == null || !TYPES.contains(e.type())) {
            return Result.rejected(RejectReason.ENUM);
        }
        // 3 time
        Instant occurredAt = parseInstant(e.occurredAt());
        Instant receivedAt = parseInstant(e.receivedAt());
        if (occurredAt == null || receivedAt == null
                || receivedAt.isBefore(now.minus(RECEIVED_PAST)) || receivedAt.isAfter(now.plus(RECEIVED_FUTURE))
                || occurredAt.isAfter(receivedAt) || occurredAt.isBefore(receivedAt.minus(MAX_SKEW))) {
            return Result.rejected(RejectReason.TIME);
        }
        // 4 shape: null pattern per type
        if (!hasShape(e)) {
            return Result.rejected(RejectReason.SHAPE);
        }
        // 5 range
        if (!inRange(e)) {
            return Result.rejected(RejectReason.RANGE);
        }
        // 6 enum: closed vocabularies
        if (!inVocabulary(e)) {
            return Result.rejected(RejectReason.ENUM);
        }
        // 7 path
        if (!pathsValid(e)) {
            return Result.rejected(RejectReason.PATH);
        }
        // 8 shape: free-form formats
        if (!formatsValid(e)) {
            return Result.rejected(RejectReason.SHAPE);
        }
        return new Result(new ValidEvent(
                UUID.fromString(e.eventId()), e.type(), occurredAt, receivedAt,
                e.visitorId() == null ? null : UUID.fromString(e.visitorId()),
                e.pageviewId() == null ? null : UUID.fromString(e.pageviewId()),
                e.seq(), e.path(), e.locale(), e.landing(), e.referrerDomain(), e.utmSource(), e.utmMedium(),
                e.utmCampaign(), e.country(), e.deviceClass(), e.browserFamily(), e.osFamily(), e.viewport(),
                e.engagedMs(), e.scrollMax(), e.fits(), e.sectionMask(), e.formStarted(), e.clickKind(),
                e.clickTarget(), e.clickArea(), e.goal()), null);
    }

    private static boolean hasShape(EventDto e) {
        Object[] pageviewContext = {e.landing(), e.referrerDomain(), e.utmSource(), e.utmMedium(), e.utmCampaign(),
                e.country(), e.deviceClass(), e.browserFamily(), e.osFamily(), e.viewport()};
        Object[] engagement = {e.engagedMs(), e.scrollMax(), e.fits(), e.sectionMask(), e.formStarted()};
        Object[] click = {e.clickKind(), e.clickTarget(), e.clickArea()};
        return switch (e.type()) {
            case PAGEVIEW -> routed(e)
                    && allPresent(e.landing(), e.deviceClass(), e.browserFamily(), e.osFamily())
                    && (Boolean.TRUE.equals(e.landing())
                    || allNull(e.referrerDomain(), e.utmSource(), e.utmMedium(), e.utmCampaign()))
                    && allNull(engagement) && allNull(click) && e.goal() == null;
            case ENGAGEMENT -> routed(e) && allPresent(engagement)
                    && allNull(pageviewContext) && allNull(click) && e.goal() == null;
            case CLICK -> routed(e) && allPresent(e.clickKind(), e.clickArea()) && clickTargetShape(e)
                    && allNull(pageviewContext) && allNull(engagement) && e.goal() == null;
            case GOAL -> isUuidV4(e.visitorId()) && allPresent(e.path(), e.locale(), e.goal())
                    && allNull(e.pageviewId(), e.seq())
                    && allNull(pageviewContext) && allNull(engagement) && allNull(click);
            case ENQUIRY -> allNull(e.visitorId(), e.pageviewId(), e.seq(), e.path(), e.locale(), e.goal())
                    && allNull(pageviewContext) && allNull(engagement) && allNull(click);
            default -> false;
        };
    }

    /** Pageview, engagement and click carry the visitor, the pageview key and a path. */
    private static boolean routed(EventDto e) {
        return isUuidV4(e.visitorId()) && isUuidV4(e.pageviewId()) && allPresent(e.seq(), e.path(), e.locale());
    }

    /** {@code clickTarget} is null exactly for mailto/tel; an unknown kind is left to the vocabulary rule. */
    private static boolean clickTargetShape(EventDto e) {
        if (!CLICK_KINDS.contains(e.clickKind())) {
            return true;
        }
        boolean targetless = TARGETLESS_CLICK_KINDS.contains(e.clickKind());
        return targetless == (e.clickTarget() == null);
    }

    private static boolean inRange(EventDto e) {
        if (e.seq() != null) {
            boolean seqOk = PAGEVIEW.equals(e.type()) ? e.seq() == 0 : e.seq() >= 1 && e.seq() <= MAX_SEQ;
            if (!seqOk) {
                return false;
            }
        }
        return (e.engagedMs() == null || (e.engagedMs() >= 0 && e.engagedMs() <= MAX_ENGAGED_MS))
                && (e.scrollMax() == null || SCROLL_STEPS.contains(e.scrollMax()))
                && (e.sectionMask() == null || (e.sectionMask() >= 0 && e.sectionMask() <= MAX_SECTION_MASK));
    }

    private static boolean inVocabulary(EventDto e) {
        return nullOr(e.locale(), LOCALES)
                && nullOr(e.deviceClass(), DEVICE_CLASSES)
                && nullOr(e.browserFamily(), BROWSER_FAMILIES)
                && nullOr(e.osFamily(), OS_FAMILIES)
                && nullOr(e.viewport(), VIEWPORTS)
                && nullOr(e.clickKind(), CLICK_KINDS)
                && nullOr(e.clickArea(), CLICK_AREAS)
                && nullOr(e.goal(), GOALS)
                && (e.country() == null
                || (COUNTRY.matcher(e.country()).matches() && !RESERVED_COUNTRIES.contains(e.country())));
    }

    private static boolean pathsValid(EventDto e) {
        if (e.path() != null && !isCanonicalPath(e.path())) {
            return false;
        }
        return !"nav".equals(e.clickKind())
                || OTHER_NAV_TARGET.equals(e.clickTarget()) || isCanonicalPath(e.clickTarget());
    }

    private static boolean formatsValid(EventDto e) {
        if (e.referrerDomain() != null && !HOST.matcher(e.referrerDomain()).matches()) {
            return false;
        }
        for (String utm : new String[]{e.utmSource(), e.utmMedium(), e.utmCampaign()}) {
            if (utm != null && (!UTM.matcher(utm).matches() || DIGIT_RUN.matcher(utm).find())) {
                return false;
            }
        }
        if (e.clickKind() == null || e.clickTarget() == null) {
            return true;
        }
        return switch (e.clickKind()) {
            case "cta", "link", "faq" -> TRACK_ID.matcher(e.clickTarget()).matches();
            case "lang" -> LOCALES.contains(e.clickTarget());
            case "outbound" -> HOST.matcher(e.clickTarget()).matches();
            default -> true; // nav was checked by the path rule
        };
    }

    static boolean isCanonicalPath(String path) {
        return path != null && path.length() <= MAX_PATH_LENGTH && PATH.matcher(path).matches();
    }

    private static boolean isUuidV4(String value) {
        return value != null && UUID_V4.matcher(value).matches();
    }

    private static Instant parseInstant(String value) {
        if (value == null || !INSTANT.matcher(value).matches()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null; // e.g. month 13; the message quotes the input and is dropped on purpose
        }
    }

    private static boolean nullOr(String value, Set<String> allowed) {
        return value == null || allowed.contains(value);
    }

    private static boolean allPresent(Object... values) {
        return Arrays.stream(values).allMatch(Objects::nonNull);
    }

    private static boolean allNull(Object... values) {
        return Arrays.stream(values).allMatch(Objects::isNull);
    }
}
