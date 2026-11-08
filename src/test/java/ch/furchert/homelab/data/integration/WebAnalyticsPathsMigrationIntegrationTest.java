package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V8__webanalytics_path_aggregates (docs/100 §7.8, rev 3.5). */
class WebAnalyticsPathsMigrationIntegrationTest extends AbstractIntegrationTest {

    static final List<String> V8 = List.of("daily_transition", "daily_funnel", "daily_group_path", "daily_entry_exit",
            "daily_intent_seen", "daily_dimension", "daily_cf_baseline", "daily_submits");

    @Autowired
    JdbcClient jdbc;

    @Test
    void historyRecordsV8AndTheTablesExist() {
        assertThat(jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '8' AND description = 'webanalytics path aggregates'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'webanalytics'")
                .query(String.class).list()).containsAll(V8);
    }

    @Test
    void v8TablesAreIdFreeAndHaveNoIpOrConversionPerAttributeColumn() {
        assertThat(jdbc.sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name IN (:tables)
                          AND (data_type IN ('uuid', 'inet') OR column_name LIKE '%visitor_id%'
                               OR column_name LIKE '%visit_id%' OR column_name LIKE '%ip%')
                        """).param("tables", V8).query(String.class).list()).isEmpty();
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'webanalytics' AND table_name = 'daily_dimension'
                        """).query(String.class).list())
                .containsExactlyInAnyOrder("day", "dimension", "value", "visits", "computed_at");
    }

    @Test
    void checksRejectOutOfContractRows() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_funnel (day, funnel_id, step, entry_locale, device_class, visits)
                VALUES (current_date, 'checkout', 1, 'de', 'desktop', 1)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_intent_seen (day, seen, visits) VALUES (current_date, 'pricing', 1)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_dimension (day, dimension, value, visits)
                VALUES (current_date, 'city', 'Zurich', 1)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO webanalytics.daily_cf_baseline (day, path, locale, requests, sampled)
                VALUES (current_date, '/', 'fr', 1, false)
                """).update()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
