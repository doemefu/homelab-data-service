package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.config.RetentionProperties;
import ch.furchert.homelab.data.netmon.api.EgressDtos.EgressTopItem;
import ch.furchert.homelab.data.netmon.api.EgressQueryRepository;
import ch.furchert.homelab.data.netmon.api.TimeWindow;
import ch.furchert.homelab.data.support.NetmonTables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily aggregate always uses UTC days (data-service#57 plan review ruling 3), also with a JVM default time zone and
 * a database session time zone of Europe/Zurich: hourly rows at 23:00Z and 00:00Z go to two different days, the
 * {@code is_new} look-back and the read path split there too.
 */
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'Europe/Zurich'")
class EgressDailyZurichTimeZoneIntegrationTest extends AbstractIntegrationTest {

    private static final TimeZone ORIGINAL = TimeZone.getDefault();

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"));
    }

    @AfterAll
    static void restore() {
        TimeZone.setDefault(ORIGINAL);
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    EgressSnapshotRepository repository;

    @BeforeEach
    void clean() {
        NetmonTables.clear(jdbc);
    }

    @Test
    void daysAreUtcDaysInAZurichSession() {
        assertThat(jdbc.sql("SHOW TIME ZONE").query(String.class).single()).isEqualTo("Europe/Zurich");
        row("2026-09-23T23:00:00Z", "34.117.59.81", 1);
        row("2026-09-24T00:00:00Z", "34.117.59.81", 2);
        repository.recomputeDay(LocalDate.parse("2026-09-23"));
        repository.recomputeDay(LocalDate.parse("2026-09-24"));

        assertThat(jdbc.sql("SELECT day::text || '|' || bytes_sent FROM netmon.egress_flow_daily ORDER BY day")
                .query(String.class).list()).containsExactly("2026-09-23|1", "2026-09-24|2");

        // The read path: a whole UTC day before the cut-off (now 2026-10-05, raw 8 d → cut-off 2026-09-28).
        EgressQueryRepository queries = new EgressQueryRepository(jdbc,
                Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC),
                new RetentionProperties(90, 180, 180, 30, 30, 90, 90, 8, 180, 32));
        List<EgressTopItem> day = queries.top(new TimeWindow(Instant.parse("2026-09-23T00:00:00Z"),
                Instant.parse("2026-09-24T00:00:00Z")), null, null, null, 10);
        assertThat(day).singleElement().satisfies(i -> assertThat(i.bytesSent()).isEqualTo(1));
    }

    @Test
    void isNewSeesThePreviousUtcDayThroughTheDailyRows() {
        row("2026-09-23T23:00:00Z", "34.117.59.81", 1);
        repository.recomputeDay(LocalDate.parse("2026-09-23"));

        repository.replace(Instant.parse("2026-09-24T01:00:00Z"), List.of(flow("34.117.59.81"), flow("140.82.121.4")));

        assertThat(jdbc.sql("""
                        SELECT host(destination_ip) || '|' || is_new FROM netmon.egress_flow_snapshots
                        WHERE window_start = '2026-09-24T01:00:00Z' ORDER BY 1
                        """).query(String.class).list())
                .containsExactly("140.82.121.4|true", "34.117.59.81|false");
        assertThat(jdbc.sql("SELECT day::text FROM netmon.egress_flow_daily ORDER BY day").query(String.class).list())
                .containsExactly("2026-09-23", "2026-09-24", "2026-09-24");
    }

    private static EgressFlow flow(String ip) {
        return new EgressFlow("mba1", "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm",
                new EgressLabels.Identity("apps", "litellm-5d8f7c9b6-x2k9p", "litellm", "litellm", "apps/litellm/litellm"),
                ip + ":443", ip + ":443", new EgressLabels.Endpoint(ip, null, 443), "external", null, 1, 1, 1, 0);
    }

    private void row(String windowStart, String ip, long bytes) {
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, namespace, pod,
                            container, workload, workload_key, destination, actual_destination, destination_ip,
                            destination_port, destination_scope, fqdn, bytes_sent, bytes_received, connects,
                            failed_connects, is_new, source)
                        VALUES (CAST(:ws AS timestamptz), CAST(:ws AS timestamptz) + interval '1 hour', 'mba1',
                                '/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm', 'apps', 'litellm-5d8f7c9b6-x2k9p', 'litellm',
                                'litellm', 'apps/litellm/litellm', :ip || ':443', :ip || ':443', CAST(:ip AS inet), 443,
                                'external', NULL, :bytes, 0, 0, 0, true, 'prometheus-coroot')
                        """)
                .param("ws", windowStart).param("ip", ip).param("bytes", bytes).update();
    }
}
