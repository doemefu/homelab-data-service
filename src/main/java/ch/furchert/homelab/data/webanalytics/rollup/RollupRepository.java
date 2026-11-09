package ch.furchert.homelab.data.webanalytics.rollup;

import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner.RolledDay;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.ClickRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.ConversionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DayRows;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.DimensionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.EntryExitRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.FunnelRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.GroupPathRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.IntentSeenRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.TransitionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.MonthlyRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.PageRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.RollupDayRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.SectionRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.SourceRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.VisitorsRow;
import ch.furchert.homelab.data.webanalytics.rollup.RollupRows.VisitsRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Data access of the {@code webanalytics-rollup} collector (docs/100 §7.5, §7.8). Every method runs inside the
 * caller's per-day transaction. Window reads are streamed with a fetch size; history reads use server-side
 * semi-joins on the window's visitors, so no id list leaves the database.
 */
@Repository
public class RollupRepository {

    static final int FETCH_SIZE = 1_000;
    /** Tables replaced per day from raw events (the CF baseline is written separately, see {@link #replaceCfBaseline}). */
    static final List<String> DAY_TABLES = List.of("daily_visits", "daily_visitors", "daily_page", "daily_section",
            "daily_click", "daily_source", "daily_conversion", "daily_transition", "daily_funnel", "daily_group_path",
            "daily_entry_exit", "daily_intent_seen", "daily_dimension", "daily_submits", "rollup_day");

    /**
     * Cloudflare GET 2xx requests to page paths per Zurich day (docs/100 §7.8, rev 3.5): the {@code /de|/en} prefix
     * becomes the page locale, the rest (without a trailing slash, {@code ''} → {@code /}) the locale-free path; the
     * first segments {@code dashboard}, {@code imprint}, {@code privacy} are not tracked pages and are excluded.
     * Grouped by the normalised expressions, so {@code /de} and {@code /de/} are one row. Never reads {@code client_ip}.
     */
    private static final String CF_BASELINE_SELECT = """
            SELECT (g.window_start AT TIME ZONE :zone)::date AS day, n.path, n.locale,
                   sum(g.request_count)::bigint AS requests, bool_or(g.sampled) AS sampled
            FROM netmon.inbound_request_groups g
            CROSS JOIN LATERAL (
                SELECT substring(g.path FROM '^/(de|en)') AS locale,
                       coalesce(nullif(rtrim(substring(g.path FROM '^/(?:de|en)(/.*)?$'), '/'), ''), '/') AS path
            ) n
            WHERE g.host = 'furchert.ch' AND g.method = 'GET' AND g.status BETWEEN 200 AND 299
              AND g.path ~ '^/(de|en)(/[a-z0-9][a-z0-9-]{0,63}){0,2}/?$'
              AND g.path !~ '^/(de|en)/(dashboard|imprint|privacy)(/|$)'
              AND g.window_start >= :from AND g.window_start < :to
            GROUP BY 1, n.path, n.locale
            """;

    private static final String WINDOW_VISITORS = """
            SELECT DISTINCT w.visitor_id FROM webanalytics.events w
            WHERE w.occurred_at >= :from AND w.occurred_at < :to
            """;

    private final NamedParameterJdbcTemplate named;
    private final NamedParameterJdbcTemplate streaming;

    public RollupRepository(NamedParameterJdbcTemplate named, DataSource dataSource) {
        this.named = named;
        JdbcTemplate fetching = new JdbcTemplate(dataSource);
        fetching.setFetchSize(FETCH_SIZE);
        this.streaming = new NamedParameterJdbcTemplate(fetching);
    }

    public List<RolledDay> rolledDays() {
        return named.query("SELECT day, final, definition_version FROM webanalytics.rollup_day ORDER BY day",
                (rs, i) -> new RolledDay(rs.getObject("day", LocalDate.class), rs.getBoolean("final"),
                        rs.getInt("definition_version")));
    }

    /** The stored {@code rollup_day} facts the freeze guard (d) needs. */
    public record StoredDay(boolean fin, int rawEvents) {
    }

    /** The day's {@code rollup_day} row, if any (freeze guard (d), read after the lock). */
    public Optional<StoredDay> storedDay(LocalDate day) {
        return named.query("SELECT final, raw_events FROM webanalytics.rollup_day WHERE day = :day",
                new MapSqlParameterSource("day", day),
                (rs, i) -> new StoredDay(rs.getBoolean("final"), rs.getInt("raw_events"))).stream().findFirst();
    }

    /** {@code pg_try_advisory_xact_lock(hashtext('webanalytics-rollup'))}; held until the transaction ends. */
    public boolean tryLock() {
        Boolean locked = named.getJdbcTemplate().queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('webanalytics-rollup'))", Boolean.class);
        return Boolean.TRUE.equals(locked);
    }

    /** {@code SET LOCAL work_mem = '16MB'} and {@code statement_timeout = '120s'} (docs/100 §7.5). */
    public void applyDaySettings() {
        named.getJdbcTemplate().execute("SET LOCAL work_mem = '16MB'");
        named.getJdbcTemplate().execute("SET LOCAL statement_timeout = '120s'");
    }

    /** Events with {@code occurred_at} in [from, to), ordered by visitor, time and id. */
    public List<RawEvent> loadWindow(Instant from, Instant to) {
        List<RawEvent> events = new ArrayList<>();
        streaming.query("""
                        SELECT event_id, event_type, occurred_at, visitor_id, pageview_id, seq, path, locale, landing,
                               referrer_domain, utm_source, utm_medium, utm_campaign, device_class, engaged_ms,
                               scroll_max, fits, section_mask, form_started, click_kind, click_target, click_area,
                               country, browser_family, os_family, viewport
                        FROM webanalytics.events
                        WHERE occurred_at >= :from AND occurred_at < :to
                        ORDER BY visitor_id, occurred_at, event_id
                        """,
                window(from, to), rs -> {
                    events.add(mapEvent(rs));
                });
        return events;
    }

    /**
     * Window visitors that have any event before {@code from}: these visitors' visits can never be new. {@code EXISTS}
     * semi-join (docs/100 §7.5), so each visitor stops at its first earlier event.
     */
    public Set<UUID> visitorsSeenBefore(Instant from, Instant to) {
        Set<UUID> seen = new HashSet<>();
        named.query("""
                        SELECT w.visitor_id FROM (%s) w
                        WHERE EXISTS (SELECT 1 FROM webanalytics.events e
                                      WHERE e.visitor_id = w.visitor_id AND e.occurred_at < :from)
                        """.formatted(WINDOW_VISITORS),
                window(from, to), rs -> {
                    seen.add(rs.getObject("visitor_id", UUID.class));
                });
        return seen;
    }

    /**
     * The earliest landing pageview before {@code to} of each window visitor (first-known channel); the caller still
     * rejects a landing later than the visit it is used for.
     */
    public Map<UUID, RawEvent> firstLandings(Instant from, Instant to) {
        Map<UUID, RawEvent> landings = new HashMap<>();
        named.query("""
                        SELECT DISTINCT ON (e.visitor_id) e.event_id, e.event_type, e.occurred_at, e.visitor_id,
                               e.pageview_id, e.seq, e.path, e.locale, e.landing, e.referrer_domain, e.utm_source,
                               e.utm_medium, e.utm_campaign, e.device_class, e.engaged_ms, e.scroll_max, e.fits,
                               e.section_mask, e.form_started, e.click_kind, e.click_target, e.click_area,
                               e.country, e.browser_family, e.os_family, e.viewport
                        FROM webanalytics.events e
                        WHERE e.event_type = 'pageview' AND e.landing AND e.occurred_at < :to
                          AND e.visitor_id IN (%s)
                        ORDER BY e.visitor_id, e.occurred_at, e.event_id
                        """.formatted(WINDOW_VISITORS),
                window(from, to), rs -> {
                    RawEvent e = mapEvent(rs);
                    landings.put(e.visitorId(), e);
                });
        return landings;
    }

    /** {@code raw_events} (all types) and {@code raw_pageviews} with {@code occurred_at} in [start, end). */
    public int[] rawCounts(Instant start, Instant end) {
        return named.queryForObject("""
                        SELECT count(*) AS events, count(*) FILTER (WHERE event_type = 'pageview') AS pageviews
                        FROM webanalytics.events WHERE occurred_at >= :from AND occurred_at < :to
                        """,
                window(start, end), (rs, i) -> new int[]{rs.getInt("events"), rs.getInt("pageviews")});
    }

    /** Deletes the day from every rollup-owned table, then inserts the new rows (one transaction, the caller's). */
    public void replaceDay(LocalDate day, Instant dayStart, Instant dayEnd, DayRows rows, VisitorsRow visitors,
                           RollupDayRow rollupDay) {
        MapSqlParameterSource dayParam = new MapSqlParameterSource("day", day);
        for (String table : DAY_TABLES) {
            named.update("DELETE FROM webanalytics." + table + " WHERE day = :day", dayParam);
        }
        batch("""
                INSERT INTO webanalytics.daily_visits (day, entry_locale, device_class, visits, new_visits,
                    returning_visits, engaged_visits, offer_visits, engaged_offer_visits, contact_intent_visits,
                    form_start_visits,
                    converting_visits, conversions, pageviews, excluded_visits)
                VALUES (:day, :locale, :device, :visits, :newVisits, :returningVisits, :engaged, :offer, :engagedOffer,
                    :contactIntent, :formStart, :converting, :conversions, :pageviews, :excluded)
                """, rows.visits(), (VisitsRow r) -> new MapSqlParameterSource("day", day)
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                .addValue("visits", r.visits()).addValue("newVisits", r.newVisits())
                .addValue("returningVisits", r.returningVisits()).addValue("engaged", r.engagedVisits())
                .addValue("offer", r.offerVisits()).addValue("engagedOffer", r.engagedOfferVisits())
                .addValue("contactIntent", r.contactIntentVisits()).addValue("formStart", r.formStartVisits())
                .addValue("converting", r.convertingVisits()).addValue("conversions", r.conversions())
                .addValue("pageviews", r.pageviews()).addValue("excluded", r.excludedVisits()));
        named.update("""
                        INSERT INTO webanalytics.daily_visitors (day, visitors, new_visitors, returning_visitors,
                            visitors_7d, visitors_28d, repeat_offer_viewers_28d)
                        VALUES (:day, :visitors, :newVisitors, :returningVisitors, :v7, :v28, :repeat28)
                        """,
                new MapSqlParameterSource("day", day).addValue("visitors", visitors.visitors())
                        .addValue("newVisitors", visitors.newVisitors())
                        .addValue("returningVisitors", visitors.returningVisitors())
                        .addValue("v7", visitors.visitors7d()).addValue("v28", visitors.visitors28d())
                        .addValue("repeat28", visitors.repeatOfferViewers28d()));
        batch("""
                INSERT INTO webanalytics.daily_page (day, path, entry_locale, device_class, pageviews, pageviews_de,
                    pageviews_en, visits_viewed, entries, exits, landing_engaged_visits, landing_converting_visits,
                    engagement_samples, engaged_ms_sum, eng_h0, eng_h1, eng_h2, eng_h3, eng_h4, eng_h5, eng_h6, eng_h7,
                    scroll_samples, scroll_25, scroll_50, scroll_75, scroll_90, scroll_100)
                VALUES (:day, :path, :locale, :device, :pageviews, :de, :en, :visitsViewed, :entries, :exits,
                    :landingEngaged, :landingConverting, :samples, :engagedSum, :h0, :h1, :h2, :h3, :h4, :h5, :h6, :h7,
                    :scrollSamples, :s25, :s50, :s75, :s90, :s100)
                """, rows.pages(), (PageRow r) -> {
            MapSqlParameterSource p = new MapSqlParameterSource("day", day).addValue("path", r.path())
                    .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                    .addValue("pageviews", r.pageviews()).addValue("de", r.pageviewsDe())
                    .addValue("en", r.pageviewsEn())
                    .addValue("visitsViewed", r.visitsViewed()).addValue("entries", r.entries())
                    .addValue("exits", r.exits()).addValue("landingEngaged", r.landingEngagedVisits())
                    .addValue("landingConverting", r.landingConvertingVisits())
                    .addValue("samples", r.engagementSamples()).addValue("engagedSum", r.engagedMsSum())
                    .addValue("scrollSamples", r.scrollSamples());
            for (int i = 0; i < 8; i++) {
                p.addValue("h" + i, r.histogram()[i]);
            }
            int[] thresholds = DayAggregator.SCROLL_THRESHOLDS;
            for (int i = 0; i < thresholds.length; i++) {
                p.addValue("s" + thresholds[i], r.scroll()[i]);
            }
            return p;
        });
        batch("""
                INSERT INTO webanalytics.daily_section (day, entry_locale, device_class, section, reached_pageviews,
                    deepest_at_exit_visits)
                VALUES (:day, :locale, :device, :section, :reached, :deepest)
                """, rows.sections(), (SectionRow r) -> new MapSqlParameterSource("day", day)
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                .addValue("section", r.section()).addValue("reached", r.reachedPageviews())
                .addValue("deepest", r.deepestAtExitVisits()));
        batch("""
                INSERT INTO webanalytics.daily_click (day, path, entry_locale, device_class, kind, target, area, clicks,
                    visits, converting_visits)
                VALUES (:day, :path, :locale, :device, :kind, :target, :area, :clicks, :visits, :converting)
                """, rows.clicks(), (ClickRow r) -> new MapSqlParameterSource("day", day).addValue("path", r.path())
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass()).addValue("kind", r.kind())
                .addValue("target", r.target()).addValue("area", r.area()).addValue("clicks", r.clicks())
                .addValue("visits", r.visits()).addValue("converting", r.convertingVisits()));
        batch("""
                INSERT INTO webanalytics.daily_source (day, entry_locale, device_class, channel, referrer_domain,
                    utm_source, utm_medium, utm_campaign, visits, new_visits, engaged_visits, offer_visits,
                    converting_visits)
                VALUES (:day, :locale, :device, :channel, :referrer, :utmSource, :utmMedium, :utmCampaign, :visits,
                    :newVisits, :engaged, :offer, :converting)
                """, rows.sources(), (SourceRow r) -> new MapSqlParameterSource("day", day)
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                .addValue("channel", r.channel()).addValue("referrer", r.referrerDomain())
                .addValue("utmSource", r.utmSource()).addValue("utmMedium", r.utmMedium())
                .addValue("utmCampaign", r.utmCampaign()).addValue("visits", r.visits())
                .addValue("newVisits", r.newVisits()).addValue("engaged", r.engagedVisits())
                .addValue("offer", r.offerVisits()).addValue("converting", r.convertingVisits()));
        batch("""
                INSERT INTO webanalytics.daily_conversion (day, entry_locale, device_class, entry_path, channel,
                    first_channel, converting_visits, conversions)
                VALUES (:day, :locale, :device, :entryPath, :channel, :firstChannel, :converting, :conversions)
                """, rows.conversions(), (ConversionRow r) -> new MapSqlParameterSource("day", day)
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                .addValue("entryPath", r.entryPath()).addValue("channel", r.channel())
                .addValue("firstChannel", r.firstChannel()).addValue("converting", r.convertingVisits())
                .addValue("conversions", r.conversions()));
        batch("""
                INSERT INTO webanalytics.daily_transition (day, entry_locale, device_class, from_path, to_path, transitions)
                VALUES (:day, :locale, :device, :from, :to, :n)
                """, rows.transitions(), (TransitionRow r) -> new MapSqlParameterSource("day", day)
                .addValue("locale", r.entryLocale()).addValue("device", r.deviceClass())
                .addValue("from", r.fromPath()).addValue("to", r.toPath()).addValue("n", r.transitions()));
        batch("""
                INSERT INTO webanalytics.daily_funnel (day, funnel_id, step, entry_locale, device_class, visits)
                VALUES (:day, :funnel, :step, :locale, :device, :visits)
                """, rows.funnels(), (FunnelRow r) -> new MapSqlParameterSource("day", day)
                .addValue("funnel", r.funnelId()).addValue("step", r.step()).addValue("locale", r.entryLocale())
                .addValue("device", r.deviceClass()).addValue("visits", r.visits()));
        batch("""
                INSERT INTO webanalytics.daily_group_path (day, path_key, visits, converting_visits)
                VALUES (:day, :key, :visits, :converting)
                """, rows.groupPaths(), (GroupPathRow r) -> new MapSqlParameterSource("day", day)
                .addValue("key", r.pathKey()).addValue("visits", r.visits())
                .addValue("converting", r.convertingVisits()));
        batch("""
                INSERT INTO webanalytics.daily_entry_exit (day, entry_path, exit_path, visits)
                VALUES (:day, :entry, :exit, :visits)
                """, rows.entryExits(), (EntryExitRow r) -> new MapSqlParameterSource("day", day)
                .addValue("entry", r.entryPath()).addValue("exit", r.exitPath()).addValue("visits", r.visits()));
        batch("""
                INSERT INTO webanalytics.daily_intent_seen (day, seen, visits) VALUES (:day, :seen, :visits)
                """, rows.intentSeen(), (IntentSeenRow r) -> new MapSqlParameterSource("day", day)
                .addValue("seen", r.seen()).addValue("visits", r.visits()));
        batch("""
                INSERT INTO webanalytics.daily_dimension (day, dimension, value, visits, engaged_visits)
                VALUES (:day, :dimension, :value, :visits, :engaged)
                """, rows.dimensions(), (DimensionRow r) -> new MapSqlParameterSource("day", day)
                .addValue("dimension", r.dimension()).addValue("value", r.value()).addValue("visits", r.visits())
                .addValue("engaged", r.engagedVisits()));
        // Goals by submission day, excluded and orphan visits included (matches daily_enquiries, docs/100 §7.8).
        named.update("""
                        INSERT INTO webanalytics.daily_submits (day, consented_submits)
                        SELECT :day, count(*) FROM webanalytics.events
                        WHERE event_type = 'goal' AND occurred_at >= :from AND occurred_at < :to
                        """,
                window(dayStart, dayEnd).addValue("day", day));
        named.update("""
                        INSERT INTO webanalytics.rollup_day (day, raw_events, raw_pageviews, visits, orphan_visits,
                            definition_version, final, rolled_up_at)
                        VALUES (:day, :rawEvents, :rawPageviews, :visits, :orphan, :version, :final, :at)
                        """,
                new MapSqlParameterSource("day", day).addValue("rawEvents", rollupDay.rawEvents())
                        .addValue("rawPageviews", rollupDay.rawPageviews()).addValue("visits", rollupDay.visits())
                        .addValue("orphan", rollupDay.orphanVisits())
                        .addValue("version", rollupDay.definitionVersion()).addValue("final", rollupDay.fin())
                        .addValue("at", utc(rollupDay.rolledUpAt())));
    }

    /** The days in [first, last] that already have {@code daily_cf_baseline} rows (the backfill skips them). */
    public List<LocalDate> cfBaselineDays(LocalDate first, LocalDate last) {
        return named.queryForList("SELECT DISTINCT day FROM webanalytics.daily_cf_baseline "
                        + "WHERE day >= :first AND day <= :last",
                new MapSqlParameterSource("first", first).addValue("last", last), LocalDate.class);
    }

    /** First {@code window_start} netmon still holds, or null. */
    public Instant firstNetmonHour() {
        return named.getJdbcTemplate().query("SELECT min(window_start) FROM netmon.inbound_request_groups",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                });
    }

    /** Whether {@code daily_cf_baseline} has rows for the day. */
    public boolean cfBaselineHasDay(LocalDate day) {
        return Boolean.TRUE.equals(named.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM webanalytics.daily_cf_baseline WHERE day = :day)",
                new MapSqlParameterSource("day", day), Boolean.class));
    }

    /** Number of baseline rows netmon yields for [from, to) (0 = nothing to replace with). */
    public int cfBaselineRowsAvailable(Instant from, Instant to, String zone) {
        Integer n = named.queryForObject("SELECT count(*) FROM (" + CF_BASELINE_SELECT + ") b",
                window(from, to).addValue("zone", zone), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Replaces the baseline rows of the Zurich days in [from, to) with netmon's current rows (nightly and backfill:
     * one day per call). {@code zone} is the analytics time zone name.
     */
    public int replaceCfBaseline(LocalDate firstDay, LocalDate lastDay, Instant from, Instant to, String zone) {
        named.update("DELETE FROM webanalytics.daily_cf_baseline WHERE day >= :first AND day <= :last",
                new MapSqlParameterSource("first", firstDay).addValue("last", lastDay));
        return named.update("INSERT INTO webanalytics.daily_cf_baseline (day, path, locale, requests, sampled) "
                + CF_BASELINE_SELECT, window(from, to).addValue("zone", zone));
    }

    public void replaceMonth(MonthlyRow row) {
        MapSqlParameterSource p = new MapSqlParameterSource("month", row.month()).addValue("visitors", row.visitors())
                .addValue("newVisitors", row.newVisitors()).addValue("returningVisitors", row.returningVisitors())
                .addValue("repeat", row.repeatOfferViewers());
        named.update("DELETE FROM webanalytics.monthly_visitors WHERE month = :month", p);
        named.update("""
                INSERT INTO webanalytics.monthly_visitors (month, visitors, new_visitors, returning_visitors,
                    repeat_offer_viewers)
                VALUES (:month, :visitors, :newVisitors, :returningVisitors, :repeat)
                """, p);
    }

    /** The newest day of the contiguous all-final run of days starting at the first rolled day. */
    public Optional<LocalDate> allFinalPrefixEnd() {
        return Optional.ofNullable(named.getJdbcTemplate().queryForObject("""
                WITH bounds AS (SELECT min(day) AS first, max(day) AS last FROM webanalytics.rollup_day),
                     gap AS (
                         SELECT min(g.day)::date AS day
                         FROM bounds, generate_series(bounds.first, bounds.last, interval '1 day') AS g(day)
                         LEFT JOIN webanalytics.rollup_day r ON r.day = g.day::date
                         WHERE r.day IS NULL OR NOT r.final)
                SELECT CASE
                           WHEN (SELECT first FROM bounds) IS NULL THEN NULL
                           WHEN (SELECT day FROM gap) IS NULL THEN (SELECT last FROM bounds)
                           WHEN (SELECT day FROM gap) = (SELECT first FROM bounds) THEN NULL
                           ELSE (SELECT day FROM gap) - 1
                       END
                """, LocalDate.class));
    }

    private <T> void batch(String sql, List<T> rows, Function<T, SqlParameterSource> params) {
        if (!rows.isEmpty()) {
            named.batchUpdate(sql, rows.stream().map(params).toArray(SqlParameterSource[]::new));
        }
    }

    private static MapSqlParameterSource window(Instant from, Instant to) {
        return new MapSqlParameterSource("from", utc(from)).addValue("to", utc(to));
    }

    private static RawEvent mapEvent(ResultSet rs) throws SQLException {
        return new RawEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                instant(rs, "occurred_at"),
                rs.getObject("visitor_id", UUID.class),
                rs.getObject("pageview_id", UUID.class),
                (Integer) rs.getObject("seq", Integer.class),
                rs.getString("path"),
                rs.getString("locale"),
                (Boolean) rs.getObject("landing", Boolean.class),
                rs.getString("referrer_domain"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
                rs.getString("device_class"),
                rs.getObject("engaged_ms", Integer.class),
                rs.getObject("scroll_max", Integer.class),
                rs.getObject("fits", Boolean.class),
                rs.getObject("section_mask", Integer.class),
                rs.getObject("form_started", Boolean.class),
                rs.getString("click_kind"),
                rs.getString("click_target"),
                rs.getString("click_area"),
                rs.getString("country"),
                rs.getString("browser_family"),
                rs.getString("os_family"),
                rs.getString("viewport"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
