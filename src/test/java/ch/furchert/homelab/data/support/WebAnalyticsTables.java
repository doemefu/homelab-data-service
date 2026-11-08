package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the webanalytics tables (WA-1 and WA-3a) between integration tests. */
public final class WebAnalyticsTables {

    private static final String[] TABLES = {"events", "daily_ingest_stats", "daily_enquiries", "rollup_day",
            "daily_visits", "daily_visitors", "monthly_visitors", "daily_page", "daily_section", "daily_click",
            "daily_source", "daily_conversion"};

    private WebAnalyticsTables() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM webanalytics." + table).update();
        }
    }
}
