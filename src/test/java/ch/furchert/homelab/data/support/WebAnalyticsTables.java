package ch.furchert.homelab.data.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties the WA-1 webanalytics tables between integration tests. */
public final class WebAnalyticsTables {

    private WebAnalyticsTables() {
    }

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM webanalytics.events").update();
        jdbc.sql("DELETE FROM webanalytics.daily_ingest_stats").update();
        jdbc.sql("DELETE FROM webanalytics.daily_enquiries").update();
    }
}
