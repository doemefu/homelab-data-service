package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.ip.IpAddresses;
import ch.furchert.homelab.data.web.NetmonApiException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** Query-parameter rules of docs/060 §7.1: time window, {@code limit}, {@code cursor} and IP literals. */
final class ApiParams {

    static final Duration MAX_WINDOW = Duration.ofDays(30);
    static final Duration DEFAULT_WINDOW = Duration.ofDays(1);

    /** Top-N lists: default 10, max 50. */
    static final int TOP_DEFAULT = 10;
    static final int TOP_MAX = 50;
    /** Paged lists: default 50, max 500. */
    static final int LIST_DEFAULT = 50;
    static final int LIST_MAX = 500;

    private ApiParams() {
    }

    /** {@code to} defaults to now, {@code from} to {@code to - defaultSpan}; {@code to - from} in (0, 30 d]. */
    static TimeWindow window(String from, String to, Duration defaultSpan, Clock clock) {
        Instant end = blank(to) ? clock.instant().truncatedTo(ChronoUnit.SECONDS) : instant(to);
        Instant start = blank(from) ? end.minus(defaultSpan) : instant(from);
        Duration span = Duration.between(start, end);
        if (span.isNegative() || span.isZero() || span.compareTo(MAX_WINDOW) > 0) {
            throw NetmonApiException.invalidWindow();
        }
        return new TimeWindow(start, end);
    }

    static int limit(String value, int defaultValue, int max) {
        if (blank(value)) {
            return defaultValue;
        }
        try {
            int limit = Integer.parseInt(value.strip());
            if (limit >= 1 && limit <= max) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw NetmonApiException.invalidParameter("limit must be an integer between 1 and " + max);
    }

    /** An optional TCP/UDP port filter, 1..65535. */
    static Integer optionalPort(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.strip());
            if (port >= 1 && port <= 65_535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw NetmonApiException.invalidParameter("dport must be an integer between 1 and 65535");
    }

    /** The canonical form of an IP literal; never resolves hostnames. */
    static String ip(String value) {
        return IpAddresses.canonical(value)
                .orElseThrow(() -> NetmonApiException.invalidParameter("ip must be an IPv4 or IPv6 address"));
    }

    static String optionalIp(String value) {
        return blank(value) ? null : ip(value);
    }

    static String optional(String value) {
        return blank(value) ? null : value;
    }

    /** Opaque keyset position {@code (occurredAt, id)}, base64url encoded. */
    record Cursor(Instant occurredAt, long id) {

        String encode() {
            String raw = occurredAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (blank(value)) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value.strip()), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Cursor(Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
            } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException e) {
                throw NetmonApiException.invalidParameter("cursor is not valid");
            }
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw NetmonApiException.invalidWindow();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
