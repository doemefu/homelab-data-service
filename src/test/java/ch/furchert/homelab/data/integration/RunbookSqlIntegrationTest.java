package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.AggregateRows;
import ch.furchert.homelab.data.support.RawEventRows;
import ch.furchert.homelab.data.support.WebAnalyticsTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ch.furchert.homelab.data.support.RollupEvents.zurich;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical WA-5 runbook SQL in {@code DEPLOYMENT.md} (#56 comment 4; homelab#351 ruling 3) runs as written against
 * the real schema: every {@code ```sql} block starting with {@code -- runbook:<name>} is executed with the example day
 * 2026-11-05, and its effect is checked.
 */
class RunbookSqlIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-05");
    private static final Pattern BLOCK = Pattern.compile("```sql\\n(-- runbook:([a-z-]+)\\n.*?)```", Pattern.DOTALL);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;

    Map<String, String> runbooks;

    @BeforeEach
    void setUp() throws IOException {
        WebAnalyticsTables.clear(jdbc);
        runbooks = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(Files.readString(Path.of("DEPLOYMENT.md")));
        while (m.find()) {
            runbooks.put(m.group(2), m.group(1));
        }
        assertThat(runbooks).containsOnlyKeys("purge-day", "reset-days", "remark-days");
        AggregateRows aggregates = new AggregateRows(jdbc);
        for (LocalDate d = DAY.minusDays(2); !d.isAfter(DAY.plusDays(2)); d = d.plusDays(1)) {
            aggregates.rollupDay(d, true);
        }
        RawEventRows raw = new RawEventRows(jdbc);
        UUID visitor = UUID.randomUUID();
        raw.pageview(visitor, zurich("2026-11-05T00:10:00"), "/");   // inside D (Zurich)
        raw.pageview(visitor, zurich("2026-11-05T23:50:00"), "/it"); // inside D
        raw.pageview(visitor, zurich("2026-11-04T23:50:00"), "/");   // D − 1
        raw.pageview(visitor, zurich("2026-11-06T00:10:00"), "/");   // D + 1
    }

    @Test
    void purgeDayDeletesTheZurichDayAndTheThreeRollupRows() throws Exception {
        run("purge-day");
        assertThat(count("SELECT count(*) FROM webanalytics.events")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT day FROM webanalytics.rollup_day ORDER BY day").query(LocalDate.class).list())
                .containsExactly(DAY.minusDays(2), DAY.plusDays(2));
    }

    @Test
    void resetDaysDeletesOnlyTheThreeRollupRows() throws Exception {
        run("reset-days");
        assertThat(count("SELECT count(*) FROM webanalytics.events")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM webanalytics.rollup_day")).isEqualTo(2);
    }

    @Test
    void remarkDaysSetsTheTwoDaysProvisional() throws Exception {
        run("remark-days");
        assertThat(jdbc.sql("SELECT day FROM webanalytics.rollup_day WHERE NOT final ORDER BY day")
                .query(LocalDate.class).list()).containsExactly(DAY, DAY.plusDays(1));
    }

    private void run(String name) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(runbooks.get(name));
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
