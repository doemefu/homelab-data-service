package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractRollupIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorWarning;
import ch.furchert.homelab.data.support.MutableClock;
import ch.furchert.homelab.data.webanalytics.rollup.WebAnalyticsRollupCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rollup against PostgreSQL (docs/100 §7.5, §7.8, §17 WA-3a); fixed Zurich clocks, events placed directly. */
class WebAnalyticsRollupIntegrationTest extends AbstractRollupIntegrationTest {

    private static final LocalDate NOV_8 = LocalDate.parse("2026-11-08");

    @Autowired
    DataSource dataSource;

    @Test
    void firstRunOnEmptyStateWritesAZeroDayAndSetsTheMark() {
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        Map<String, Object> day = jdbc.sql("SELECT * FROM webanalytics.rollup_day").query().singleRow();
        assertThat(day).containsEntry("day", java.sql.Date.valueOf(NOV_8)).containsEntry("final", true)
                .containsEntry("raw_events", 0).containsEntry("visits", 0).containsEntry("definition_version", 1);
        assertThat(lastWindowEnd()).isEqualTo(zurich("2026-11-09T00:00:00"));
    }

    @Test
    void nightRunWritesYesterdayProvisionalAndTheMorningRunFinalisesIt() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/automation", null, null);
        MutableClock clock = clockAt(zurich("2026-11-09T01:12:00"));
        WebAnalyticsRollupCollector collector = collector(clock);

        collector.collect();
        assertThat(finalOf(NOV_8)).isFalse();
        assertThat(lastWindowEnd()).isNull(); // the all-final prefix is still empty

        clock.set(zurich("2026-11-09T07:30:00"));
        collector.collect();
        assertThat(finalOf(NOV_8)).isTrue();
        assertThat(lastWindowEnd()).isEqualTo(zurich("2026-11-09T00:00:00"));
    }

    @Test
    void gapIsCaughtUpAfterAnOutage() {
        MutableClock clock = clockAt(zurich("2026-11-02T07:30:00"));
        collector(clock).collect(); // 2026-11-01
        clock.set(zurich("2026-11-06T07:30:00"));
        collector(clock).collect(); // 2026-11-02 .. 2026-11-05
        assertThat(jdbc.sql("SELECT day FROM webanalytics.rollup_day WHERE final ORDER BY day")
                .query(LocalDate.class).list())
                .containsExactly(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"),
                        LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05"));
    }

    @Test
    void identitiesHoldOverAWeekIncludingAnExcludedAndACrossMidnightVisit() {
        UUID regular = UUID.randomUUID();
        UUID repeat = UUID.randomUUID();
        UUID midnight = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        for (int d = 2; d <= 8; d++) {
            Instant noon = zurich("2026-11-0" + d + "T12:00:00");
            UUID pv = rows.landing(regular, noon, "/", "google.com", null);
            rows.engagement(regular, pv, 1, noon.plusSeconds(20), "/", 12_000, 0);
            rows.pageview(regular, noon.plusSeconds(60), "/automation");
        }
        rows.landing(repeat, zurich("2026-11-03T09:00:00"), "/it", null, null);
        rows.landing(repeat, zurich("2026-11-06T09:00:00"), "/it", null, null);
        rows.landing(midnight, zurich("2026-11-05T23:50:00"), "/", null, null);
        rows.pageview(midnight, zurich("2026-11-06T00:10:00"), "/about");
        for (int i = 0; i < 101; i++) {
            rows.pageview(bot, zurich("2026-11-07T08:00:00").plusSeconds(40L * i), i % 2 == 0 ? "/" : "/it");
        }
        rollDailyFrom("2026-11-02", "2026-11-09");

        long pageSum = sum("SELECT coalesce(sum(pageviews), 0) FROM webanalytics.daily_page");
        long visitPageviews = sum("SELECT coalesce(sum(pageviews), 0) FROM webanalytics.daily_visits");
        long visits = sum("SELECT coalesce(sum(visits), 0) FROM webanalytics.daily_visits");
        assertThat(pageSum).isEqualTo(visitPageviews).isPositive();
        assertThat(sum("SELECT coalesce(sum(entries), 0) FROM webanalytics.daily_page")).isEqualTo(visits);
        assertThat(sum("SELECT coalesce(sum(exits), 0) FROM webanalytics.daily_page")).isEqualTo(visits);
        assertThat(sum("SELECT coalesce(sum(excluded_visits), 0) FROM webanalytics.daily_visits")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.daily_visitors WHERE new_visitors + returning_visitors <> visitors")
                .query(Long.class).single()).isZero();
        // The visit starting 2026-11-05 23:50 counts on 2026-11-05 with both pages.
        assertThat(sum("SELECT visits FROM webanalytics.daily_visits WHERE day = '2026-11-05'")).isEqualTo(2);
        assertThat(sum("SELECT coalesce(sum(visits), 0) FROM webanalytics.daily_visits WHERE day = '2026-11-06'"))
                .isEqualTo(2);

        Map<String, Object> nov8 = jdbc.sql("SELECT * FROM webanalytics.daily_visitors WHERE day = '2026-11-08'")
                .query().singleRow();
        long maxDaily = sum("SELECT max(visitors) FROM webanalytics.daily_visitors WHERE day BETWEEN '2026-11-02' AND '2026-11-08'");
        long sumDaily = sum("SELECT sum(visitors) FROM webanalytics.daily_visitors WHERE day BETWEEN '2026-11-02' AND '2026-11-08'");
        int v7 = (Integer) nov8.get("visitors_7d");
        assertThat((long) v7).isBetween(maxDaily, sumDaily);
        // Exact uniques: regular (7 days), repeat (2 days), midnight (1 visit) count once each; the bot is excluded.
        assertThat(v7).isEqualTo(3);
        assertThat(nov8.get("visitors_28d")).isEqualTo(3);
        assertThat(nov8.get("repeat_offer_viewers_28d")).isEqualTo(2); // regular (7 offer visits), repeat (2)
    }

    @Test
    void visitorWithAnEvent300DaysBackIsReturning() {
        UUID returning = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        rows.landing(returning, zurich("2026-11-08T10:00:00").minus(Duration.ofDays(300)), "/", null, null);
        rows.landing(returning, zurich("2026-11-08T10:00:00"), "/", null, null);
        rows.landing(fresh, zurich("2026-11-08T11:00:00"), "/", null, null);
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        Map<String, Object> visits = jdbc.sql("SELECT * FROM webanalytics.daily_visits WHERE day = '2026-11-08'")
                .query().singleRow();
        assertThat(visits).containsEntry("visits", 2).containsEntry("new_visits", 1).containsEntry("returning_visits", 1);
        Map<String, Object> visitors = jdbc.sql("SELECT * FROM webanalytics.daily_visitors WHERE day = '2026-11-08'")
                .query().singleRow();
        assertThat(visitors).containsEntry("visitors", 2).containsEntry("new_visitors", 1)
                .containsEntry("returning_visitors", 1);
    }

    @Test
    void firstKnownChannelComesFromTheEarliestLandingOnAnEarlierDay() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-02T10:00:00"), "/automation", "linkedin.com", null);
        rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/contact", null, null);
        rows.goal(visitor, zurich("2026-11-08T10:05:00"));
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();

        Map<String, Object> conversion = jdbc.sql("SELECT * FROM webanalytics.daily_conversion WHERE day = '2026-11-08'")
                .query().singleRow();
        assertThat(conversion).containsEntry("channel", "direct").containsEntry("first_channel", "social")
                .containsEntry("entry_path", "/contact").containsEntry("converting_visits", 1)
                .containsEntry("conversions", 1);
    }

    @Test
    void reRunIsIdenticalApartFromTimestamps() {
        UUID visitor = UUID.randomUUID();
        UUID pv = rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/automation", "google.ch", null);
        rows.engagement(visitor, pv, 1, zurich("2026-11-08T10:01:00"), "/automation", 45_000, 0b1001);
        rows.click(visitor, pv, 2, zurich("2026-11-08T10:01:30"), "/automation", "cta", "automation-hero");
        rows.goal(visitor, zurich("2026-11-08T10:05:00"));
        MutableClock clock = clockAt(zurich("2026-11-09T07:30:00"));
        collector(clock).collect();
        Map<String, List<Map<String, Object>>> first = snapshot();

        jdbc.sql("UPDATE webanalytics.rollup_day SET final = false WHERE day = '2026-11-08'").update();
        clock.advance(Duration.ofMinutes(5));
        collector(clock).collect();

        assertThat(snapshot()).isEqualTo(first);
        assertThat(finalOf(NOV_8)).isTrue();
    }

    @Test
    void rawCountsExcludeTheSixHourMargins() {
        UUID visitor = UUID.randomUUID();
        rows.landing(visitor, zurich("2026-11-07T22:00:00"), "/", null, null);
        rows.landing(visitor, zurich("2026-11-08T10:00:00"), "/it", null, null);
        rows.landing(visitor, zurich("2026-11-09T01:00:00"), "/about", null, null);
        collector(clockAt(zurich("2026-11-09T07:30:00"))).collect();
        assertThat(jdbc.sql("SELECT raw_events, raw_pageviews FROM webanalytics.rollup_day WHERE day = '2026-11-08'")
                .query().singleRow()).containsEntry("raw_events", 1).containsEntry("raw_pageviews", 1);
    }

    @Test
    void monthlyVisitorsCoverTheMonthAndDayTwoAlsoRecomputesThePreviousMonth() {
        UUID october = UUID.randomUUID();
        UUID november = UUID.randomUUID();
        rows.landing(october, zurich("2026-10-31T12:00:00"), "/", null, null);
        rows.landing(november, zurich("2026-11-01T12:00:00"), "/", null, null);
        rows.landing(october, zurich("2026-11-02T12:00:00"), "/", null, null);
        MutableClock clock = clockAt(zurich("2026-11-01T07:30:00"));
        collector(clock).collect(); // 2026-10-31
        clock.set(zurich("2026-11-03T07:30:00"));
        collector(clock).collect(); // 2026-11-01, 2026-11-02

        assertThat(jdbc.sql("SELECT visitors FROM webanalytics.monthly_visitors WHERE month = '2026-10-01'")
                .query(Integer.class).single()).isEqualTo(1);
        Map<String, Object> nov = jdbc.sql("SELECT * FROM webanalytics.monthly_visitors WHERE month = '2026-11-01'")
                .query().singleRow();
        assertThat(nov).containsEntry("visitors", 2).containsEntry("new_visitors", 1)
                .containsEntry("returning_visitors", 1);
    }

    @Test
    void heldAdvisoryLockStopsTheRunWithoutWriting() throws Exception {
        try (Connection other = dataSource.getConnection(); Statement s = other.createStatement()) {
            s.execute("SELECT pg_advisory_lock(hashtext('webanalytics-rollup'))");
            try {
                assertThatThrownBy(() -> collector(clockAt(zurich("2026-11-09T07:30:00"))).collect())
                        .isInstanceOf(CollectorWarning.class)
                        .hasMessageContaining("advisory lock");
                assertThat(jdbc.sql("SELECT count(*) FROM webanalytics.rollup_day").query(Long.class).single())
                        .isZero();
            } finally {
                s.execute("SELECT pg_advisory_unlock(hashtext('webanalytics-rollup'))");
            }
        }
    }

    private void rollDailyFrom(String firstDay, String lastRunDay) {
        MutableClock clock = clockAt(zurich(LocalDate.parse(firstDay).plusDays(1) + "T07:30:00"));
        WebAnalyticsRollupCollector collector = collector(clock);
        for (LocalDate run = LocalDate.parse(firstDay).plusDays(1); !run.isAfter(LocalDate.parse(lastRunDay));
             run = run.plusDays(1)) {
            clock.set(zurich(run + "T07:30:00"));
            collector.collect();
        }
    }

    private boolean finalOf(LocalDate day) {
        return jdbc.sql("SELECT final FROM webanalytics.rollup_day WHERE day = :day").param("day", day)
                .query(Boolean.class).single();
    }

    private Instant lastWindowEnd() {
        return jdbc.sql("SELECT last_window_end FROM netmon.collector_state WHERE collector = 'webanalytics-rollup'")
                .query((rs, i) -> {
                    OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional().orElse(null);
    }

    private long sum(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    /** Every rollup table as sorted rows without {@code computed_at} / {@code rolled_up_at} (docs/100 §7.5, Q3). */
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> tables = new java.util.TreeMap<>();
        for (String table : List.of("rollup_day", "daily_visits", "daily_visitors", "monthly_visitors", "daily_page",
                "daily_section", "daily_click", "daily_source", "daily_conversion")) {
            List<Map<String, Object>> list = jdbc.sql("SELECT * FROM webanalytics." + table).query().listOfRows().stream()
                    .map(row -> {
                        Map<String, Object> copy = new java.util.TreeMap<>(row);
                        copy.remove("computed_at");
                        copy.remove("rolled_up_at");
                        return (Map<String, Object>) copy;
                    })
                    .sorted(java.util.Comparator.comparing(Object::toString))
                    .toList();
            tables.put(table, list);
        }
        return tables;
    }
}
