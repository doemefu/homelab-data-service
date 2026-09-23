package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.retention.RetentionJob;
import ch.furchert.homelab.data.netmon.retention.RetentionTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention mechanics (docs/060 §3.5) against a test-only table: NM-0 ships no telemetry table,
 * so the probe table stands in for the tables NM-1..NM-4 will register as RetentionTargets.
 */
class RetentionJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CollectorRunner runner;

    @BeforeEach
    void createProbeTable() {
        jdbc.sql("CREATE TABLE netmon.retention_probe (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, window_start timestamptz NOT NULL)")
                .update();
        // 12 000 expired rows = three delete batches of 5 000 (5 000, 5 000, 2 000, then 0).
        jdbc.sql("INSERT INTO netmon.retention_probe (window_start) SELECT now() - interval '40 days' FROM generate_series(1, 12000)")
                .update();
        jdbc.sql("INSERT INTO netmon.retention_probe (window_start) SELECT now() - interval '1 day' FROM generate_series(1, 3)")
                .update();
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = 'retention'").update();
    }

    @AfterEach
    void dropProbeTable() {
        jdbc.sql("DROP TABLE IF EXISTS netmon.retention_probe").update();
        jdbc.sql("DELETE FROM netmon.collector_state WHERE collector = 'retention'").update();
    }

    @Test
    void deletesExpiredRowsInBatchesAndRecordsSuccess() {
        RetentionJob job = new RetentionJob(jdbc, List.of(new RetentionTarget("retention_probe", "window_start", 30)), runner);

        boolean ran = runner.run(job);

        assertThat(ran).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.retention_probe").query(Long.class).single()).isEqualTo(3L);
        Map<String, Object> state = jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'retention'")
                .query().singleRow();
        assertThat(state.get("last_success_at")).isNotNull();
        assertThat(state.get("last_attempt_at")).isNotNull();
        assertThat(state.get("consecutive_failures")).isEqualTo(0);
        assertThat(state.get("last_error")).isNull();
        assertThat(state.get("last_error_code")).isNull();
    }

    @Test
    void failureRecordsClassNameOnlyAndIncrementsCounter() {
        RetentionJob job = new RetentionJob(jdbc, List.of(new RetentionTarget("no_such_table", "window_start", 30)), runner);

        assertThat(runner.run(job)).isFalse();
        assertThat(runner.run(job)).isFalse();

        Map<String, Object> state = jdbc.sql("SELECT * FROM netmon.collector_state WHERE collector = 'retention'")
                .query().singleRow();
        assertThat(state.get("consecutive_failures")).isEqualTo(2);
        assertThat(state.get("last_error_code")).isEqualTo("internal");
        // Foreign exception messages (SQL, URLs) are never persisted — class name only.
        assertThat((String) state.get("last_error")).isEqualTo("BadSqlGrammarException");
        assertThat(state.get("last_success_at")).isNull();
    }

    @Test
    void noTargets_isASuccessfulNoOp() {
        RetentionJob job = new RetentionJob(jdbc, List.of(), runner);

        assertThat(runner.run(job)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.retention_probe").query(Long.class).single()).isEqualTo(12003L);
    }
}
