package ch.furchert.homelab.data.netmon.retention;

import ch.furchert.homelab.data.netmon.collector.CollectorRunner;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Daily retention (docs/060 §3.5, docs/100 §7.7): for every registered {@link RetentionTarget}, delete rows older
 * than its retention in batches (default 5 000) until none are left, logging only
 * {@code [retention] <table> deleted=<n>} ({@code webanalytics.events} with its schema). Each sub-project registers
 * the targets for its own tables (NM-1: {@code InboundRetentionConfig}; WA-5: {@code WebAnalyticsRetentionConfig}).
 * The job never truncates, and the API has no manual deletion path.
 */
@Component
public class RetentionJob implements NetmonCollector {

    public static final String NAME = "retention";
    static final int BATCH_SIZE = RetentionTarget.DEFAULT_BATCH_SIZE;

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;
    private final ObjectProvider<RetentionTarget> targetProvider;
    private final List<RetentionTarget> fixedTargets;
    private final CollectorRunner runner;

    @Autowired
    public RetentionJob(JdbcClient jdbc, ObjectProvider<RetentionTarget> targets, CollectorRunner runner) {
        this.jdbc = jdbc;
        this.targetProvider = targets;
        this.fixedTargets = null;
        this.runner = runner;
    }

    /** For tests: a job with an explicit target list instead of the registered beans. */
    public RetentionJob(JdbcClient jdbc, List<RetentionTarget> targets, CollectorRunner runner) {
        this.jdbc = jdbc;
        this.targetProvider = null;
        this.fixedTargets = List.copyOf(targets);
        this.runner = runner;
    }

    /** 03:30 UTC, outside the Longhorn (02:00) and restic (03:00) local-time windows. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void scheduledRun() {
        runner.run(this);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration cadence() {
        return Duration.ofDays(1);
    }

    @Override
    public void collect() {
        for (RetentionTarget target : targets()) {
            long deleted = purge(target);
            log.info("[retention] {} deleted={}", target.qualifiedName(), deleted);
        }
    }

    private List<RetentionTarget> targets() {
        return fixedTargets != null ? fixedTargets : targetProvider.orderedStream().toList();
    }

    private long purge(RetentionTarget target) {
        // Schema and identifiers are validated by RetentionTarget; only the day count is a bind parameter.
        // The guard, if any, is a fixed SQL fragment from code on the alias t.
        String table = target.schema() + "." + target.table();
        String sql = "DELETE FROM " + table
                + " WHERE ctid IN (SELECT t.ctid FROM " + table + " t"
                + " WHERE t." + target.timeColumn() + " < now() - make_interval(days => :days)"
                + (target.guard() == null ? "" : " AND (" + target.guard() + ")")
                + " LIMIT " + target.batchSize() + ")";
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("days", target.days()).update();
            total += deleted;
        } while (deleted > 0);
        return total;
    }
}
