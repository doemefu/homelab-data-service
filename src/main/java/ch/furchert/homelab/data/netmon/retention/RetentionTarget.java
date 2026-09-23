package ch.furchert.homelab.data.netmon.retention;

import java.util.regex.Pattern;

/**
 * One netmon table under the retention job (docs/060 §3.5). Sub-projects NM-1..NM-4 register one
 * bean per table, reading {@code netmon.retention.<table>-days}. Construction fails (and with it the
 * application start) for {@code days < 1} or for names that are not plain lower-case identifiers,
 * because the names are interpolated into SQL.
 *
 * @param table      table name inside the {@code netmon} schema, e.g. {@code inbound_request_groups}
 * @param timeColumn the {@code timestamptz} column compared against the cutoff, e.g. {@code window_start}
 * @param days       rows older than this many days are deleted
 */
public record RetentionTarget(String table, String timeColumn, int days) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public RetentionTarget {
        if (table == null || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("retention: invalid table name: " + table);
        }
        if (timeColumn == null || !IDENTIFIER.matcher(timeColumn).matches()) {
            throw new IllegalArgumentException("retention: invalid time column for " + table + ": " + timeColumn);
        }
        if (days < 1) {
            throw new IllegalArgumentException("retention: netmon.retention days for " + table + " must be >= 1, was " + days);
        }
    }
}
