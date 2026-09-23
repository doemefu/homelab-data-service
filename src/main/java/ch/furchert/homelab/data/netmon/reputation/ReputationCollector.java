package ch.furchert.homelab.data.netmon.reputation;

import ch.furchert.homelab.data.config.AbuseIpDbProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorException;
import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.CollectorState;
import ch.furchert.homelab.data.netmon.collector.CollectorStateRepository;
import ch.furchert.homelab.data.netmon.collector.ErrorCode;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code reputation} (docs/060 §4.1, §4.5): on-demand AbuseIPDB checks of public IPs that crossed a
 * threshold. Unavailable (never runs, {@code enabled=false} in {@code /status}) while no API key is set,
 * so no IP leaves the cluster until the owner approves the key.
 * <ul>
 *   <li>Budget: {@code daily-budget} checks per UTC day, {@code per-run} per run; the day's count lives in
 *       {@code collector_state.cursor} as {@code YYYY-MM-DD:count}.</li>
 *   <li>Candidates: not blocklisted, never checked or checked more than 7 d ago, and meeting one threshold,
 *       in priority order — (2) a firewall event other than skip/log in 24 h, (3) ≥ 50 requests in 24 h
 *       with ≥ 50 % status ≥ 400, (4) top 5 by requests in 24 h. Threshold (1), failed logins, arrives
 *       with the NM-4 {@code login_events} table.</li>
 *   <li>A 429 stops the run and marks the day's budget as exhausted; 401/403 and an unreachable AbuseIPDB
 *       stop the run. A failure specific to one IP (other HTTP errors, an incomplete body) counts against
 *       the budget, sets {@code abuseipdb_checked_at} without a score so the IP rests for 7 days, and the
 *       run continues with the next candidate.</li>
 * </ul>
 */
@Component
public class ReputationCollector implements NetmonCollector {

    public static final String NAME = "reputation";

    private static final Logger log = LoggerFactory.getLogger(ReputationCollector.class);

    static final String CANDIDATES = """
            WITH eligible AS (
                SELECT e.ip FROM netmon.ip_enrichment e
                WHERE NOT e.blocklisted
                  AND (e.abuseipdb_checked_at IS NULL OR e.abuseipdb_checked_at < CAST(:now AS timestamptz) - interval '7 days')
            ),
            firewall AS (
                SELECT f.client_ip AS ip, 2 AS priority, count(*) AS weight
                FROM netmon.firewall_events f
                WHERE f.occurred_at >= CAST(:now AS timestamptz) - interval '24 hours' AND f.action NOT IN ('skip', 'log')
                GROUP BY f.client_ip
            ),
            requests AS (
                SELECT r.client_ip AS ip, sum(r.request_count) AS total,
                       sum(r.request_count) FILTER (WHERE r.status >= 400) AS errors
                FROM netmon.inbound_request_groups r
                WHERE r.window_end > CAST(:now AS timestamptz) - interval '24 hours'
                GROUP BY r.client_ip
            ),
            error_heavy AS (
                SELECT ip, 3 AS priority, total AS weight FROM requests
                WHERE total >= 50 AND coalesce(errors, 0) * 2 >= total
            ),
            top_talkers AS (
                SELECT ip, 4 AS priority, total AS weight FROM requests ORDER BY total DESC LIMIT 5
            ),
            ranked AS (
                SELECT c.ip, min(c.priority) AS priority, max(c.weight) AS weight
                FROM (SELECT * FROM firewall UNION ALL SELECT * FROM error_heavy UNION ALL SELECT * FROM top_talkers) c
                JOIN eligible USING (ip)
                GROUP BY c.ip
            )
            SELECT host(ip) FROM ranked ORDER BY priority, weight DESC, ip LIMIT :limit
            """;

    private final AbuseIpDbClient client;
    private final JdbcClient jdbc;
    private final CollectorStateRepository state;
    private final AbuseIpDbProperties properties;
    private final CollectorRunner runner;
    private final Clock clock;

    public ReputationCollector(AbuseIpDbClient client, JdbcClient jdbc, CollectorStateRepository state,
                               AbuseIpDbProperties properties, CollectorRunner runner, Clock clock) {
        this.client = client;
        this.jdbc = jdbc;
        this.state = state;
        this.properties = properties;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */30 * * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return Duration.ofMinutes(30);
    }

    @Override
    public boolean available() {
        return properties.hasKey();
    }

    @Override
    public void collect() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate today = now.toLocalDate();
        int used = usedToday(today);
        int allowed = Math.min(properties.perRun(), properties.dailyBudget() - used);
        if (allowed <= 0) {
            log.info("[{}] daily budget exhausted", NAME);
            return;
        }
        List<String> candidates = jdbc.sql(CANDIDATES)
                .param("now", now)
                .param("limit", allowed)
                .query(String.class)
                .list();
        int checked = 0;
        int failed = 0;
        for (String ip : candidates) {
            AbuseIpDbClient.Reputation reputation;
            try {
                reputation = client.check(ip);
            } catch (AbuseIpDbClient.PerIpFailure e) {
                used++;
                failed++;
                state.updateCursor(NAME, cursor(today, used));
                jdbc.sql("UPDATE netmon.ip_enrichment SET abuseipdb_checked_at = :now WHERE ip = CAST(:ip AS inet)")
                        .param("now", now)
                        .param("ip", ip)
                        .update();
                continue;
            } catch (CollectorException e) {
                if (e.code() == ErrorCode.RATE_LIMITED) {
                    state.updateCursor(NAME, cursor(today, properties.dailyBudget()));
                }
                throw e;
            }
            used++;
            checked++;
            state.updateCursor(NAME, cursor(today, used));
            jdbc.sql("""
                            UPDATE netmon.ip_enrichment
                            SET abuseipdb_score = :score, abuseipdb_reports = :reports, abuseipdb_checked_at = :now
                            WHERE ip = CAST(:ip AS inet)
                            """)
                    .param("score", (short) reputation.score())
                    .param("reports", reputation.reports())
                    .param("now", now)
                    .param("ip", ip)
                    .update();
        }
        log.info("[{}] checked={} failed={} usedToday={}", NAME, checked, failed, used);
    }

    private int usedToday(LocalDate today) {
        String cursor = state.find(NAME).map(CollectorState::cursor).orElse(null);
        if (cursor == null) {
            return 0;
        }
        int colon = cursor.indexOf(':');
        if (colon < 0 || !cursor.substring(0, colon).equals(today.toString())) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String cursor(LocalDate day, int count) {
        return day + ":" + count;
    }
}
