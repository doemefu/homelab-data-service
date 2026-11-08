package ch.furchert.homelab.data.webanalytics.ingest;

/** Contract limits of {@code POST /api/webanalytics/events} (docs/100 §6.2). */
final class IngestLimits {

    /** Body cap in bytes (Content-Length and counted stream). */
    static final int MAX_BODY_BYTES = 65_536;
    /** Events per batch; more is a 413 so furchert-ch splits the batch instead of dropping it (§5.10). */
    static final int MAX_EVENTS = 200;
    /** Upper bound of one {@code edgeDrops} count; larger values make the batch a 400. */
    static final int MAX_EDGE_DROP_COUNT = 1_000_000;

    private IngestLimits() {
    }
}
