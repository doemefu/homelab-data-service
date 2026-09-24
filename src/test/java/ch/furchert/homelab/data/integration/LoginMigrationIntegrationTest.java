package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.retention.RetentionJob;
import ch.furchert.homelab.data.netmon.retention.RetentionTarget;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5__netmon_login_events (docs/060 §3.3, §3.4) and the NM-4 retention target (§3.5). */
class LoginMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String HMAC = "a".repeat(64);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollectorRunner runner;
    @Autowired
    ObjectProvider<RetentionTarget> targets;

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    @Test
    void historyRecordsV5() {
        Boolean success = jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '5' AND description = 'netmon login events'
                        """)
                .query(Boolean.class).single();
        assertThat(success).isTrue();
    }

    @Test
    void indexesFollowTheSpec() {
        List<String> indexes = jdbc.sql("""
                        SELECT indexdef FROM pg_indexes WHERE schemaname = 'netmon' AND tablename = 'login_events'
                        """)
                .query(String.class).list();
        assertThat(indexes).anyMatch(d -> d.contains("UNIQUE") && d.contains("(event_id)"))
                .anyMatch(d -> d.endsWith("(occurred_at)"))
                .anyMatch(d -> d.endsWith("(client_ip, occurred_at)"))
                .anyMatch(d -> d.endsWith("(username_hmac, occurred_at)"));
    }

    @Test
    void eventIdIsUnique() {
        insert("'0b6f1e2a-1111-4c1d-9a0e-000000000001'", "now()", "'failure'", "'remote-addr'", "'" + HMAC + "'", "NULL");
        assertThatThrownBy(() -> insert("'0b6f1e2a-1111-4c1d-9a0e-000000000001'", "now()", "'failure'",
                "'remote-addr'", "'" + HMAC + "'", "NULL")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checksRejectValuesOutsideTheContract() {
        assertThatThrownBy(() -> insert("gen_random_uuid()", "now()", "'denied'", "'remote-addr'", "'" + HMAC + "'", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("gen_random_uuid()", "now()", "'failure'", "'x-forwarded-for'", "'" + HMAC + "'", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("gen_random_uuid()", "now()", "'failure'", "'remote-addr'", "'" + "A".repeat(64) + "'", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A subject only on success (docs/060 §7.6).
        assertThatThrownBy(() -> insert("gen_random_uuid()", "now()", "'failure'", "'remote-addr'", "'" + HMAC + "'", "'dominic'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert("gen_random_uuid()", "now()", "'success'", "'cf-connecting-ip'", "'" + HMAC + "'", "'dominic'");
    }

    @Test
    void retentionKeeps180Days() {
        assertThat(targets.orderedStream().map(t -> t.table() + ":" + t.timeColumn() + ":" + t.days()).toList())
                .contains("login_events:occurred_at:180");
        insert("gen_random_uuid()", "now() - interval '181 days'", "'failure'", "'remote-addr'", "'" + HMAC + "'", "NULL");
        insert("gen_random_uuid()", "now() - interval '179 days'", "'failure'", "'remote-addr'", "'" + HMAC + "'", "NULL");

        assertThat(runner.run(new RetentionJob(jdbc, targets.orderedStream().toList(), runner))).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM netmon.login_events").query(Long.class).single()).isEqualTo(1L);
    }

    /** SQL fragments are fixed test literals. */
    private void insert(String eventId, String occurredAt, String outcome, String ipSource, String hmac, String subject) {
        jdbc.sql("""
                INSERT INTO netmon.login_events (event_id, occurred_at, outcome, client_ip, ip_source, username_hmac,
                    subject, user_agent, source_service, source)
                VALUES (CAST(%s AS uuid), %s, %s, '203.0.113.7', %s, %s, %s, 'curl/8', 'auth-service', 'auth-service')
                """.formatted(eventId, occurredAt, outcome, ipSource, hmac, subject)).update();
    }
}
