package ch.furchert.homelab.data.webanalytics.api;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only queries of the web analytics read API (docs/100 §8.1). Aggregates only: every query reads the WA-3a
 * tables, {@code rollup_day} or {@code daily_enquiries}, never {@code webanalytics.events}, and every aggregate query
 * joins {@code rollup_day} on {@code final}, so a provisional day is never served (rev 3.4).
 */
@Repository
class AggregateQueryRepository {

    private final NamedParameterJdbcTemplate named;

    AggregateQueryRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** A range and the optional entry-locale and device filters, rendered into SQL for table alias {@code t}. */
    record Scope(LocalDate from, LocalDate to, String locale, String device) {

        String join() {
            return " JOIN webanalytics.rollup_day r ON r.day = t.day AND r.final ";
        }

        String where() {
            StringBuilder sql = new StringBuilder(" WHERE t.day >= :from AND t.day <= :to");
            if (locale != null) {
                sql.append(" AND t.entry_locale = :locale");
            }
            if (device != null) {
                sql.append(" AND t.device_class = :device");
            }
            return sql.toString();
        }

        MapSqlParameterSource params() {
            return new MapSqlParameterSource("from", from).addValue("to", to).addValue("locale", locale)
                    .addValue("device", device);
        }
    }

    Optional<LocalDate> lastCompleteDay() {
        return Optional.ofNullable(named.getJdbcTemplate().queryForObject(
                "SELECT max(day) FROM webanalytics.rollup_day WHERE final", LocalDate.class));
    }

    /** The newest provisional day newer than {@code after} (any provisional day when {@code after} is null). */
    Optional<LocalDate> provisionalDay(LocalDate after) {
        return Optional.ofNullable(named.queryForObject("""
                        SELECT max(day) FROM webanalytics.rollup_day
                        WHERE NOT final AND (CAST(:after AS date) IS NULL OR day > CAST(:after AS date))
                        """,
                new MapSqlParameterSource("after", after), LocalDate.class));
    }

    record VisitCounts(long visits, long newVisits, long returningVisits, long engagedVisits, long offerVisits,
                       long engagedOfferVisits, long contactIntentVisits, long formStartVisits, long convertingVisits,
                       long conversions, long pageviews, long excludedVisits) {
    }

    private static final String VISIT_SUMS = """
            coalesce(sum(t.visits), 0) AS visits, coalesce(sum(t.new_visits), 0) AS new_visits,
            coalesce(sum(t.returning_visits), 0) AS returning_visits,
            coalesce(sum(t.engaged_visits), 0) AS engaged_visits,
            coalesce(sum(t.offer_visits), 0) AS offer_visits,
            coalesce(sum(t.engaged_offer_visits), 0) AS engaged_offer_visits,
            coalesce(sum(t.contact_intent_visits), 0) AS contact_intent_visits,
            coalesce(sum(t.form_start_visits), 0) AS form_start_visits,
            coalesce(sum(t.converting_visits), 0) AS converting_visits, coalesce(sum(t.conversions), 0) AS conversions,
            coalesce(sum(t.pageviews), 0) AS pageviews, coalesce(sum(t.excluded_visits), 0) AS excluded_visits
            """;

    VisitCounts visitTotals(Scope scope) {
        return named.queryForObject("SELECT " + VISIT_SUMS + " FROM webanalytics.daily_visits t" + scope.join()
                + scope.where(), scope.params(), (rs, i) -> visitCounts(rs));
    }

    Map<LocalDate, VisitCounts> visitsByDay(Scope scope) {
        Map<LocalDate, VisitCounts> byDay = new TreeMap<>();
        named.query("SELECT t.day, " + VISIT_SUMS + " FROM webanalytics.daily_visits t" + scope.join() + scope.where()
                + " GROUP BY t.day", scope.params(), rs -> {
                    byDay.put(rs.getObject("day", LocalDate.class), visitCounts(rs));
                });
        return byDay;
    }

    long enquiriesTotal(LocalDate from, LocalDate to) {
        Long total = named.queryForObject("""
                        SELECT coalesce(sum(submissions), 0) FROM webanalytics.daily_enquiries
                        WHERE day >= :from AND day <= :to
                        """,
                new MapSqlParameterSource("from", from).addValue("to", to), Long.class);
        return total == null ? 0 : total;
    }

    Map<LocalDate, Long> enquiriesByDay(LocalDate from, LocalDate to) {
        Map<LocalDate, Long> byDay = new TreeMap<>();
        named.query("""
                        SELECT day, submissions FROM webanalytics.daily_enquiries WHERE day >= :from AND day <= :to
                        """,
                new MapSqlParameterSource("from", from).addValue("to", to), rs -> {
                    byDay.put(rs.getObject("day", LocalDate.class), rs.getLong("submissions"));
                });
        return byDay;
    }

    /** {@code visitors_7d} or {@code visitors_28d} of a final day; empty when the day has no final row. */
    Optional<Long> rollingVisitors(LocalDate day, int basisDays) {
        String column = basisDays == 7 ? "visitors_7d" : "visitors_28d";
        return named.query("SELECT t." + column + " AS value FROM webanalytics.daily_visitors t" +
                        " JOIN webanalytics.rollup_day r ON r.day = t.day AND r.final WHERE t.day = :day",
                new MapSqlParameterSource("day", day), (rs, i) -> rs.getLong("value")).stream().findFirst();
    }

    /** {@code monthly_visitors.visitors} of a month whose last day is final. */
    Optional<Long> monthlyVisitors(LocalDate month, LocalDate monthEnd) {
        return named.query("""
                        SELECT m.visitors FROM webanalytics.monthly_visitors m
                        JOIN webanalytics.rollup_day r ON r.day = :monthEnd AND r.final
                        WHERE m.month = :month
                        """,
                new MapSqlParameterSource("month", month).addValue("monthEnd", monthEnd),
                (rs, i) -> rs.getLong("visitors")).stream().findFirst();
    }

    record PageAgg(String path, long pageviews, long de, long en, long visitsViewed, long entries, long exits,
                   long landingEngaged, long landingConverting, long samples, long engagedSum, long[] histogram,
                   long scrollSamples, long[] scroll) {
    }

    private static final String PAGE_SUMS = """
            sum(t.pageviews) AS pageviews, sum(t.pageviews_de) AS de, sum(t.pageviews_en) AS en,
            sum(t.visits_viewed) AS visits_viewed, sum(t.entries) AS entries, sum(t.exits) AS exits,
            sum(t.landing_engaged_visits) AS landing_engaged, sum(t.landing_converting_visits) AS landing_converting,
            sum(t.engagement_samples) AS samples, sum(t.engaged_ms_sum) AS engaged_sum,
            sum(t.eng_h0) AS h0, sum(t.eng_h1) AS h1, sum(t.eng_h2) AS h2, sum(t.eng_h3) AS h3, sum(t.eng_h4) AS h4,
            sum(t.eng_h5) AS h5, sum(t.eng_h6) AS h6, sum(t.eng_h7) AS h7, sum(t.scroll_samples) AS scroll_samples,
            sum(t.scroll_25) AS s25, sum(t.scroll_50) AS s50, sum(t.scroll_75) AS s75, sum(t.scroll_90) AS s90,
            sum(t.scroll_100) AS s100
            """;

    List<PageAgg> pages(Scope scope, String path) {
        String pathFilter = path == null ? "" : " AND t.path = :path";
        return named.query("SELECT t.path, " + PAGE_SUMS + " FROM webanalytics.daily_page t" + scope.join()
                        + scope.where() + pathFilter + " GROUP BY t.path",
                scope.params().addValue("path", path), (rs, i) -> pageAgg(rs));
    }

    record PageDay(LocalDate day, long pageviews, long visitsViewed) {
    }

    List<PageDay> pageDaily(Scope scope, String path) {
        return named.query("SELECT t.day, sum(t.pageviews) AS pageviews, sum(t.visits_viewed) AS visits_viewed"
                        + " FROM webanalytics.daily_page t" + scope.join() + scope.where()
                        + " AND t.path = :path GROUP BY t.day ORDER BY t.day",
                scope.params().addValue("path", path),
                (rs, i) -> new PageDay(rs.getObject("day", LocalDate.class), rs.getLong("pageviews"),
                        rs.getLong("visits_viewed")));
    }

    record ClickAgg(String kind, String target, String path, String area, long clicks, long visits,
                    long convertingVisits) {
    }

    List<ClickAgg> clicks(Scope scope, String path, String kind) {
        StringBuilder extra = new StringBuilder();
        if (path != null) {
            extra.append(" AND t.path = :path");
        }
        if (kind != null) {
            extra.append(" AND t.kind = :kind");
        }
        return named.query("SELECT t.kind, t.target, t.path, t.area, sum(t.clicks) AS clicks, sum(t.visits) AS visits,"
                        + " sum(t.converting_visits) AS converting FROM webanalytics.daily_click t" + scope.join()
                        + scope.where() + extra + " GROUP BY t.kind, t.target, t.path, t.area",
                scope.params().addValue("path", path).addValue("kind", kind),
                (rs, i) -> new ClickAgg(rs.getString("kind"), rs.getString("target"), rs.getString("path"),
                        rs.getString("area"), rs.getLong("clicks"), rs.getLong("visits"), rs.getLong("converting")));
    }

    record SectionAgg(String section, long reached, long deepestAtExit) {
    }

    List<SectionAgg> sections(Scope scope) {
        return named.query("SELECT t.section, sum(t.reached_pageviews) AS reached,"
                        + " sum(t.deepest_at_exit_visits) AS deepest FROM webanalytics.daily_section t" + scope.join()
                        + scope.where() + " GROUP BY t.section",
                scope.params(),
                (rs, i) -> new SectionAgg(rs.getString("section"), rs.getLong("reached"), rs.getLong("deepest")));
    }

    record SourceAgg(String channel, String referrer, String utmSource, String utmMedium, String utmCampaign,
                     long visits, long newVisits, long engagedVisits, long offerVisits, long convertingVisits) {
    }

    List<SourceAgg> sources(Scope scope) {
        return named.query("SELECT t.channel, t.referrer_domain, t.utm_source, t.utm_medium, t.utm_campaign,"
                        + " sum(t.visits) AS visits, sum(t.new_visits) AS new_visits,"
                        + " sum(t.engaged_visits) AS engaged, sum(t.offer_visits) AS offer,"
                        + " sum(t.converting_visits) AS converting FROM webanalytics.daily_source t" + scope.join()
                        + scope.where()
                        + " GROUP BY t.channel, t.referrer_domain, t.utm_source, t.utm_medium, t.utm_campaign",
                scope.params(),
                (rs, i) -> new SourceAgg(rs.getString("channel"), rs.getString("referrer_domain"),
                        rs.getString("utm_source"), rs.getString("utm_medium"), rs.getString("utm_campaign"),
                        rs.getLong("visits"), rs.getLong("new_visits"), rs.getLong("engaged"), rs.getLong("offer"),
                        rs.getLong("converting")));
    }

    record ConversionAgg(String entryPath, String channel, String firstChannel, long convertingVisits,
                         long conversions) {
    }

    List<ConversionAgg> conversions(Scope scope) {
        return named.query("SELECT t.entry_path, t.channel, t.first_channel, sum(t.converting_visits) AS converting,"
                        + " sum(t.conversions) AS conversions FROM webanalytics.daily_conversion t" + scope.join()
                        + scope.where() + " GROUP BY t.entry_path, t.channel, t.first_channel",
                scope.params(),
                (rs, i) -> new ConversionAgg(rs.getString("entry_path"), rs.getString("channel"),
                        rs.getString("first_channel"), rs.getLong("converting"), rs.getLong("conversions")));
    }

    private static VisitCounts visitCounts(ResultSet rs) throws SQLException {
        return new VisitCounts(rs.getLong("visits"), rs.getLong("new_visits"), rs.getLong("returning_visits"),
                rs.getLong("engaged_visits"), rs.getLong("offer_visits"), rs.getLong("engaged_offer_visits"),
                rs.getLong("contact_intent_visits"), rs.getLong("form_start_visits"), rs.getLong("converting_visits"),
                rs.getLong("conversions"), rs.getLong("pageviews"), rs.getLong("excluded_visits"));
    }

    private static PageAgg pageAgg(ResultSet rs) throws SQLException {
        long[] histogram = new long[8];
        for (int i = 0; i < 8; i++) {
            histogram[i] = rs.getLong("h" + i);
        }
        long[] scroll = {rs.getLong("s25"), rs.getLong("s50"), rs.getLong("s75"), rs.getLong("s90"),
                rs.getLong("s100")};
        return new PageAgg(rs.getString("path"), rs.getLong("pageviews"), rs.getLong("de"), rs.getLong("en"),
                rs.getLong("visits_viewed"), rs.getLong("entries"), rs.getLong("exits"), rs.getLong("landing_engaged"),
                rs.getLong("landing_converting"), rs.getLong("samples"), rs.getLong("engaged_sum"), histogram,
                rs.getLong("scroll_samples"), scroll);
    }
}
