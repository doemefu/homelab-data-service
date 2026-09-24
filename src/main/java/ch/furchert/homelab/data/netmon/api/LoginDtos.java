package ch.furchert.homelab.data.netmon.api;

import java.time.Instant;
import java.util.List;

/** Response shapes of the NM-4 endpoints, field for field as docs/060 §7.2 defines them. */
public final class LoginDtos {

    private LoginDtos() {
    }

    public record LoginSummary(LoginTotals totals, List<IpLogins> byIp, List<SubjectLogins> bySubject,
                               List<LoginTimelineBucket> timeline) {
    }

    public record LoginTotals(long success, long failure, long locked) {
    }

    /** {@code country}/{@code blocklisted}/{@code abuseScore} come from {@code ip_enrichment} (public IPs only). */
    public record IpLogins(String ip, long success, long failure, long locked, String country, boolean blocklisted,
                           Integer abuseScore) {
    }

    /**
     * {@code failureSameHmac} counts {@code failure} events whose username HMAC equals one seen on this subject's
     * successes, which links failed attempts to known accounts without storing attempted usernames.
     */
    public record SubjectLogins(String subject, long success, long failureSameHmac) {
    }

    public record LoginTimelineBucket(Instant bucketStart, long success, long failure, long locked) {
    }

    /** {@code usernameHmacPrefix} is the first 8 hex characters of the HMAC; the full value is never served. */
    public record LoginEventItem(Instant occurredAt, String outcome, String clientIp, String ipSource, String subject,
                                 String usernameHmacPrefix, String userAgent, String country, boolean blocklisted) {
    }

    public record LoginEventPage(List<LoginEventItem> items, String nextCursor) {
    }

    /** The {@code logins} block of {@code /ips/{ip}}. */
    public record IpLoginCounts(long success, long failure, long locked) {
    }
}
