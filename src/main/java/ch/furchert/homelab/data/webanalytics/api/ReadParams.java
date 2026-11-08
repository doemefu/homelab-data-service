package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.web.NetmonApiException;
import ch.furchert.homelab.data.web.ProblemCode;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query parameters of the web analytics read API (docs/100 §8.1, rev 3.4): Zurich-date range, compare, filters and
 * list limit. Validation always runs; the range is resolved against {@code lastCompleteDay} so that provisional days
 * are never served.
 */
final class ReadParams {

    static final int DEFAULT_SPAN_DAYS = 28;
    static final int MAX_SPAN_DAYS = 366;
    static final int LIMIT_DEFAULT = 20;
    static final int LIMIT_MAX = 100;
    static final Set<String> LOCALES = Set.of("de", "en");
    static final Set<String> DEVICES = Set.of("desktop", "mobile", "tablet");

    /** A resolved, non-empty range; {@code previousFrom}/{@code previousTo} are null with {@code compare=none}. */
    record Range(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, boolean monthBasis) {

        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }

        boolean hasPrevious() {
            return previousFrom != null;
        }
    }

    /**
     * The parsed request; {@code range} is null for the empty state (no final day yet, or nothing left after
     * clamping).
     */
    record Query(Range range, LocalDate lastCompleteDay, String locale, String device, ReadDtos.Filters filters) {

        boolean filtered() {
            return locale != null || device != null;
        }
    }

    private ReadParams() {
    }

    /**
     * @param filtersApply whether {@code locale}/{@code device} apply to the endpoint (§8.1 table); if not, a given
     *                     filter is echoed under {@code ignored}
     */
    static Query parse(String fromParam, String toParam, String compareParam, String localeParam, String deviceParam,
                       boolean filtersApply, LocalDate lastCompleteDay) {
        LocalDate from = date(fromParam);
        LocalDate to = date(toParam);
        if (from != null && to != null && from.isAfter(to)) {
            throw invalidWindow();
        }
        boolean compare = compare(compareParam);
        String locale = filter(localeParam, LOCALES, "locale must be de or en");
        String device = filter(deviceParam, DEVICES, "device must be desktop, mobile or tablet");
        ReadDtos.Filters filters = filters(locale, device, filtersApply);
        String appliedLocale = filtersApply ? locale : null;
        String appliedDevice = filtersApply ? device : null;
        if (lastCompleteDay == null) {
            return new Query(null, null, appliedLocale, appliedDevice, filters);
        }
        LocalDate resolvedTo = to == null || to.isAfter(lastCompleteDay) ? lastCompleteDay : to;
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(DEFAULT_SPAN_DAYS - 1L);
        if (resolvedTo.isBefore(resolvedFrom)) {
            return new Query(null, lastCompleteDay, appliedLocale, appliedDevice, filters); // nothing final in range
        }
        if (ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) + 1 > MAX_SPAN_DAYS) {
            throw invalidWindow();
        }
        boolean monthBasis = resolvedFrom.getDayOfMonth() == 1
                && resolvedTo.equals(YearMonth.from(resolvedFrom).atEndOfMonth());
        LocalDate previousFrom = null;
        LocalDate previousTo = null;
        if (compare) {
            if (monthBasis) {
                YearMonth previous = YearMonth.from(resolvedFrom).minusMonths(1);
                previousFrom = previous.atDay(1);
                previousTo = previous.atEndOfMonth();
            } else {
                long days = ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) + 1;
                previousFrom = resolvedFrom.minusDays(days);
                previousTo = resolvedFrom.minusDays(1);
            }
        }
        return new Query(new Range(resolvedFrom, resolvedTo, previousFrom, previousTo, monthBasis), lastCompleteDay,
                appliedLocale, appliedDevice, filters);
    }

    static int limit(String value) {
        return limit(value, LIMIT_DEFAULT);
    }

    /** {@code limit} with an endpoint-specific default (the detail drilldown uses 5, docs/100 §8.1, rev 3.5). */
    static int limit(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit >= 1 && limit <= LIMIT_MAX) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // fall through: the message is never returned
        }
        throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                "limit must be an integer from 1 to " + LIMIT_MAX);
    }

    /**
     * Clamps the range to {@code definitionFrom}, the first final day of the current rollup definition
     * ({@code /funnels}, {@code /paths}; docs/100 §8.2, rev 3.5): {@code from} and the previous range start no earlier;
     * a previous range that ends before it is dropped; no such day yet, or nothing left, gives the empty range.
     */
    static Query clampToDefinition(Query query, LocalDate definitionFrom) {
        Range r = query.range();
        if (r == null) {
            return query;
        }
        if (definitionFrom == null || r.to().isBefore(definitionFrom)) {
            return new Query(null, query.lastCompleteDay(), query.locale(), query.device(), query.filters());
        }
        if (!r.from().isBefore(definitionFrom) && (!r.hasPrevious() || !r.previousFrom().isBefore(definitionFrom))) {
            return query;
        }
        LocalDate from = r.from().isBefore(definitionFrom) ? definitionFrom : r.from();
        LocalDate previousFrom = null;
        LocalDate previousTo = null;
        if (r.hasPrevious() && !r.previousTo().isBefore(definitionFrom)) {
            previousFrom = r.previousFrom().isBefore(definitionFrom) ? definitionFrom : r.previousFrom();
            previousTo = r.previousTo();
        }
        boolean monthBasis = r.monthBasis() && from.equals(r.from());
        return new Query(new Range(from, r.to(), previousFrom, previousTo, monthBasis), query.lastCompleteDay(),
                query.locale(), query.device(), query.filters());
    }

    static ReadDtos.Range rangeDto(Query query) {
        Range r = query.range();
        if (r == null) {
            return new ReadDtos.Range(null, null, 0, query.lastCompleteDay(), null);
        }
        ReadDtos.Period previous = r.hasPrevious() ? new ReadDtos.Period(r.previousFrom(), r.previousTo()) : null;
        return new ReadDtos.Range(r.from(), r.to(), r.days(), query.lastCompleteDay(), previous);
    }

    private static ReadDtos.Filters filters(String locale, String device, boolean apply) {
        Map<String, String> applied = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        if (locale != null) {
            if (apply) {
                applied.put("locale", locale);
            } else {
                ignored.add("locale");
            }
        }
        if (device != null) {
            if (apply) {
                applied.put("device", device);
            } else {
                ignored.add("device");
            }
        }
        return new ReadDtos.Filters(applied, ignored);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw invalidWindow();
        }
    }

    private static boolean compare(String value) {
        if (value == null || value.isBlank() || "previous".equals(value)) {
            return true;
        }
        if ("none".equals(value)) {
            return false;
        }
        throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                "compare must be previous or none");
    }

    private static String filter(String value, Set<String> allowed, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER, message);
        }
        return value;
    }

    private static NetmonApiException invalidWindow() {
        return new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_WINDOW,
                "from and to must be ISO dates (YYYY-MM-DD), from <= to, and the range at most 366 days");
    }
}
