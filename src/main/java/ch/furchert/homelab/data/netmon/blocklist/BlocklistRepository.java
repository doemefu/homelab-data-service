package ch.furchert.homelab.data.netmon.blocklist;

import ch.furchert.homelab.data.netmon.ip.Cidr;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** {@code netmon.blocklist_snapshots} (insert) and {@code netmon.blocklist_entries} (replace per list). */
@Repository
public class BlocklistRepository {

    /** The snapshot the current entries of a list were loaded from. */
    public record CurrentSnapshot(long id, String etag, String sha256) {
    }

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public BlocklistRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** The snapshot referenced by the list's current entries, if the list was ever applied. */
    public Optional<CurrentSnapshot> current(BlocklistList list) {
        return jdbc.sql("""
                        SELECT s.id, s.etag, s.sha256
                        FROM netmon.blocklist_snapshots s
                        WHERE s.id = (SELECT max(e.snapshot_id) FROM netmon.blocklist_entries e WHERE e.list_name = :list)
                        """)
                .param("list", list.listName())
                .query((rs, i) -> new CurrentSnapshot(rs.getLong("id"), rs.getString("etag"), rs.getString("sha256")))
                .optional();
    }

    public void recordUnchanged(BlocklistList list, Instant fetchedAt, String url, String etag, String sha256) {
        insertSnapshot(list, fetchedAt, url, "unchanged", null, etag, sha256, null);
    }

    public void recordFailed(BlocklistList list, Instant fetchedAt, String url, String error) {
        insertSnapshot(list, fetchedAt, url, "failed", null, null, null, error);
    }

    /** Inserts an {@code applied} snapshot and replaces the list's entries with it, in one transaction. */
    @Transactional
    public long apply(BlocklistList list, Instant fetchedAt, String url, String etag, String sha256, List<Cidr> entries) {
        long snapshotId = insertSnapshot(list, fetchedAt, url, "applied", entries.size(), etag, sha256, null);
        jdbc.sql("DELETE FROM netmon.blocklist_entries WHERE list_name = :list")
                .param("list", list.listName())
                .update();
        SqlParameterSource[] batch = entries.stream()
                .map(cidr -> new MapSqlParameterSource()
                        .addValue("list", list.listName())
                        .addValue("cidr", cidr.toString())
                        .addValue("snapshotId", snapshotId)
                        .addValue("source", list.source()))
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate("""
                INSERT INTO netmon.blocklist_entries (list_name, cidr, snapshot_id, source)
                VALUES (:list, CAST(:cidr AS cidr), :snapshotId, :source)
                """, batch);
        return snapshotId;
    }

    private long insertSnapshot(BlocklistList list, Instant fetchedAt, String url, String outcome, Integer entryCount,
                                String etag, String sha256, String error) {
        return jdbc.sql("""
                        INSERT INTO netmon.blocklist_snapshots
                            (list_name, fetched_at, source_url, outcome, entry_count, etag, sha256, error, source)
                        VALUES (:list, :fetchedAt, :url, :outcome, :entryCount, :etag, :sha256, :error, :source)
                        RETURNING id
                        """)
                .param("list", list.listName())
                .param("fetchedAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .param("url", url)
                .param("outcome", outcome)
                .param("entryCount", entryCount)
                .param("etag", etag)
                .param("sha256", sha256)
                .param("error", error)
                .param("source", list.source())
                .query(Long.class)
                .single();
    }
}
