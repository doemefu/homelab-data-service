package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V7__webanalytics_core_aggregates (docs/100 §7.8, rev 3.4). */
class WebAnalyticsAggregatesMigrationIntegrationTest extends AbstractIntegrationTest {

    static final List<String> AGGREGATES = List.of("rollup_day", "daily_visits", "daily_visitors", "monthly_visitors",
            "daily_page", "daily_section", "daily_click", "daily_source", "daily_conversion");

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        WebAnalyticsTables.clear(jdbc);
    }

    @Test
    void historyRecordsV7AndTheTablesExist() {
        assertThat(jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '7' AND description = 'webanalytics core aggregates'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'webanalytics'")
                .query(String.class).list()).containsAll(AGGREGATES);
    }

    @Test
    void aggregatesAreIdFree() {
        // §7.8 (rev 3.4): no uuid column and no visitor/visit id column in any aggregate table.
        List<String> offending = jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name IN (:tables)
                          AND (data_type = 'uuid' OR column_name LIKE '%visitor_id%' OR column_name LIKE '%visit_id%')
                        """)
                .param("tables", AGGREGATES).query(String.class).list();
        assertThat(offending).isEmpty();
    }

    @Test
    void rollupDayHasTheFinalFlagAndCountsAreNotNull() {
        assertThat(jdbc.sql("""
                        SELECT data_type || ':' || is_nullable FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name = 'rollup_day' AND column_name = 'final'
                        """).query(String.class).single()).isEqualTo("boolean:NO");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name IN (:tables) AND is_nullable = 'YES'
                        """).param("tables", AGGREGATES).query(Long.class).single()).isZero();
    }

    @Test
    void checksRejectOutOfContractRows() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_visits (day, entry_locale, device_class, visits, new_visits,
                    returning_visits, engaged_visits, offer_visits, engaged_offer_visits, contact_intent_visits,
                    form_start_visits, converting_visits, conversions, pageviews, excluded_visits)
                VALUES (current_date, 'de', 'desktop', -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_source (day, entry_locale, device_class, channel, visits, new_visits,
                    engaged_visits, offer_visits, converting_visits)
                VALUES (current_date, 'de', 'desktop', 'email', 1, 0, 0, 0, 0)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.monthly_visitors (month, visitors, new_visitors, returning_visitors,
                    repeat_offer_viewers)
                VALUES ('2026-11-02', 0, 0, 0, 0)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
