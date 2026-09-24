package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.LoginDtos.IpLoginCounts;
import ch.furchert.homelab.data.netmon.api.LoginDtos.IpLogins;
import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginEventItem;
import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginTimelineBucket;
import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginTotals;
import ch.furchert.homelab.data.netmon.api.LoginDtos.SubjectLogins;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Read-side SQL for the login endpoints (docs/060 §7.2). Every query selects {@code occurred_at} in {@code [from, to)}. */
@Repository
public class LoginQueryRepository {

    private static final String COUNTS = """
            count(*) FILTER (WHERE l.outcome = 'success') AS success,
            count(*) FILTER (WHERE l.outcome = 'failure') AS failure,
            count(*) FILTER (WHERE l.outcome = 'locked')  AS locked
            """;

    private final JdbcClient jdbc;

    public LoginQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LoginTotals totals(TimeWindow window) {
        return bind(jdbc.sql("SELECT " + COUNTS + """
                        FROM netmon.login_events l
                        WHERE l.occurred_at >= :from AND l.occurred_at < :to
                        """), window)
                .query((rs, i) -> new LoginTotals(rs.getLong("success"), rs.getLong("failure"), rs.getLong("locked")))
                .single();
    }

    /** IPs with the most unsuccessful attempts (failure + locked) first; events without an IP are not listed. */
    public List<IpLogins> byIp(TimeWindow window, int limit) {
        return bind(jdbc.sql("SELECT host(l.client_ip) AS ip, " + COUNTS + """
                               , max(e.country) AS country,
                               coalesce(bool_or(e.blocklisted), false) AS blocklisted,
                               max(e.abuseipdb_score) AS abuse_score
                        FROM netmon.login_events l
                        LEFT JOIN netmon.ip_enrichment e ON e.ip = l.client_ip
                        WHERE l.occurred_at >= :from AND l.occurred_at < :to AND l.client_ip IS NOT NULL
                        GROUP BY l.client_ip
                        ORDER BY count(*) FILTER (WHERE l.outcome <> 'success') DESC, count(*) DESC, l.client_ip
                        LIMIT :limit
                        """), window)
                .param("limit", limit)
                .query((rs, i) -> new IpLogins(rs.getString("ip"), rs.getLong("success"), rs.getLong("failure"),
                        rs.getLong("locked"), rs.getString("country"), rs.getBoolean("blocklisted"),
                        integer(rs, "abuse_score")))
                .list();
    }

    /**
     * Subjects with a success in the window, or with failures in the window whose HMAC matches any retained success
     * of that subject (so an attack on an account that did not log in during the window still shows up).
     */
    public List<SubjectLogins> bySubject(TimeWindow window, int limit) {
        return bind(jdbc.sql("""
                        WITH known AS (
                            SELECT DISTINCT subject, username_hmac
                            FROM netmon.login_events
                            WHERE outcome = 'success' AND subject IS NOT NULL
                        ),
                        successes AS (
                            SELECT subject, count(*) AS n
                            FROM netmon.login_events
                            WHERE outcome = 'success' AND subject IS NOT NULL
                              AND occurred_at >= :from AND occurred_at < :to
                            GROUP BY subject
                        ),
                        failures AS (
                            SELECT k.subject, count(*) AS n
                            FROM netmon.login_events l
                            JOIN known k ON k.username_hmac = l.username_hmac
                            WHERE l.outcome = 'failure' AND l.occurred_at >= :from AND l.occurred_at < :to
                            GROUP BY k.subject
                        )
                        SELECT subject, coalesce(s.n, 0) AS success, coalesce(f.n, 0) AS failure_same_hmac
                        FROM successes s
                        FULL JOIN failures f USING (subject)
                        ORDER BY failure_same_hmac DESC, success DESC, subject
                        LIMIT :limit
                        """), window)
                .param("limit", limit)
                .query((rs, i) -> new SubjectLogins(rs.getString("subject"), rs.getLong("success"),
                        rs.getLong("failure_same_hmac")))
                .list();
    }

    /** Buckets with data only, ascending; {@code unit} is {@code hour} or {@code day} (UTC). */
    public List<LoginTimelineBucket> timeline(TimeWindow window, String unit) {
        return bind(jdbc.sql("SELECT date_trunc(CAST(:unit AS text), l.occurred_at, 'UTC') AS bucket, " + COUNTS + """
                        FROM netmon.login_events l
                        WHERE l.occurred_at >= :from AND l.occurred_at < :to
                        GROUP BY bucket
                        ORDER BY bucket
                        """), window)
                .param("unit", unit)
                .query((rs, i) -> new LoginTimelineBucket(instant(rs, "bucket"), rs.getLong("success"),
                        rs.getLong("failure"), rs.getLong("locked")))
                .list();
    }

    /** Events newest first, starting after {@code cursor}; up to {@code limit} rows plus their keyset positions. */
    public List<EventRow> events(TimeWindow window, String outcome, String ip, ApiParams.Cursor cursor, int limit) {
        return bind(jdbc.sql("""
                        SELECT l.id, l.occurred_at, l.outcome, host(l.client_ip) AS client_ip, l.ip_source, l.subject,
                               left(l.username_hmac, 8) AS hmac_prefix, l.user_agent, e.country,
                               coalesce(e.blocklisted, false) AS blocklisted
                        FROM netmon.login_events l
                        LEFT JOIN netmon.ip_enrichment e ON e.ip = l.client_ip
                        WHERE l.occurred_at >= :from AND l.occurred_at < :to
                          AND (CAST(:outcome AS text) IS NULL OR l.outcome = CAST(:outcome AS text))
                          AND (CAST(:ip AS inet) IS NULL OR l.client_ip = CAST(:ip AS inet))
                          AND (CAST(:cursorAt AS timestamptz) IS NULL
                               OR (l.occurred_at, l.id) < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS bigint)))
                        ORDER BY l.occurred_at DESC, l.id DESC
                        LIMIT :limit
                        """), window)
                .param("outcome", outcome)
                .param("ip", ip)
                .param("cursorAt", cursor == null ? null : utc(cursor.occurredAt()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("limit", limit)
                .query((rs, i) -> new EventRow(new ApiParams.Cursor(instant(rs, "occurred_at"), rs.getLong("id")),
                        new LoginEventItem(instant(rs, "occurred_at"), rs.getString("outcome"),
                                rs.getString("client_ip"), rs.getString("ip_source"), rs.getString("subject"),
                                rs.getString("hmac_prefix"), rs.getString("user_agent"), rs.getString("country"),
                                rs.getBoolean("blocklisted"))))
                .list();
    }

    /** One login event plus its keyset position. */
    public record EventRow(ApiParams.Cursor position, LoginEventItem item) {
    }

    /** Outcome counts of one IP in the window (zeros when it never logged in). */
    public IpLoginCounts ipSummary(TimeWindow window, String ip) {
        return bind(jdbc.sql("SELECT " + COUNTS + """
                        FROM netmon.login_events l
                        WHERE l.client_ip = CAST(:ip AS inet) AND l.occurred_at >= :from AND l.occurred_at < :to
                        """), window)
                .param("ip", ip)
                .query((rs, i) -> new IpLoginCounts(rs.getLong("success"), rs.getLong("failure"), rs.getLong("locked")))
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, TimeWindow window) {
        return spec.param("from", utc(window.from())).param("to", utc(window.to()));
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
