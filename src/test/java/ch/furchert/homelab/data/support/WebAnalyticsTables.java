package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the webanalytics tables (WA-1, WA-3a, WA-3b) between integration tests. */
public final class WebAnalyticsTables {

    private static final String[] TABLES = {"events", "daily_ingest_stats", "daily_enquiries", "rollup_day",
            "daily_visits", "daily_visitors", "monthly_visitors", "daily_page", "daily_section", "daily_click",
            "daily_source", "daily_conversion", "daily_transition", "daily_funnel", "daily_group_path",
            "daily_entry_exit", "daily_intent_seen", "daily_dimension", "daily_cf_baseline", "daily_submits"};

    private WebAnalyticsTables() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM webanalytics." + table).update();
        }
    }
}
