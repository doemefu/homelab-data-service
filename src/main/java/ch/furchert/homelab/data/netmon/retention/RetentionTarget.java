package ch.furchert.homelab.data.netmon.retention;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One table under the retention job (docs/060 §3.5, docs/100 §7.7). Sub-projects register one bean per table, reading
 * {@code netmon.retention.<table>-days} (or {@code webanalytics.retention.events-days}). Construction fails (and with
 * it the application start) for {@code days < 1}, a batch size below 1, a schema other than {@code netmon} or
 * {@code webanalytics}, or names that are not plain lower-case identifiers, because the names are interpolated into
 * SQL.
 *
 * @param table      table name inside {@code schema}, e.g. {@code inbound_request_groups}
 * @param timeColumn the {@code timestamptz} column compared against the cutoff, e.g. {@code window_start}
 * @param days       rows older than this many days are deleted
 * @param guard      optional extra SQL condition on the row alias {@code t} that must also hold for a row to
 *                   be deleted (e.g. the §3.5 {@code blocklist_snapshots} FK exclusion); fixed text written in
 *                   code, never configuration
 * @param schema     {@code netmon} (default) or {@code webanalytics}
 * @param batchSize  rows deleted per statement (default {@value #DEFAULT_BATCH_SIZE})
 */
public record RetentionTarget(String table, String timeColumn, int days, String guard, String schema, int batchSize) {

    public static final int DEFAULT_BATCH_SIZE = 5_000;
    private static final Set<String> SCHEMAS = Set.of("netmon", "webanalytics");

    public RetentionTarget(String table, String timeColumn, int days) {
        this(table, timeColumn, days, null);
    }

    public RetentionTarget(String table, String timeColumn, int days, String guard) {
        this(table, timeColumn, days, guard, "netmon", DEFAULT_BATCH_SIZE);
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public RetentionTarget {
        if (schema == null || !SCHEMAS.contains(schema)) {
            throw new IllegalArgumentException("retention: invalid schema: " + schema);
        }
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("retention: invalid table name: " + table);
        }
        if (timeColumn == null || !IDENTIFIER.matcher(timeColumn).matches()) {
            throw new IllegalArgumentException("retention: invalid time column for " + table + ": " + timeColumn);
        }
        if (days < 1) {
            throw new IllegalArgumentException("retention: netmon.retention days for " + table + " must be >= 1, was " + days);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("retention: batch size for " + table + " must be >= 1, was " + batchSize);
        }
    }

    /** The name in the log line: the bare table for {@code netmon} (unchanged), {@code schema.table} otherwise. */
    public String qualifiedName() {
        return "netmon".equals(schema) ? table : schema + "." + table;
    }
}
