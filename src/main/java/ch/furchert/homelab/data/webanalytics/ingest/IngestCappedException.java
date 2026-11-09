package ch.furchert.homelab.data.webanalytics.ingest;

/**
 * A batch would exceed a cap (docs/100 §6.3, §6.4): thrown inside the batch transaction so nothing of the batch is
 * stored or counted (all-or-nothing; its {@code edgeDrops} are not counted either). {@link IngestController} answers
 * 429 and records the capped events in their own transaction.
 */
public class IngestCappedException extends RuntimeException {

    private final transient IngestCaps.Capped capped;

    public IngestCappedException(IngestCaps.Capped capped) {
        super("ingest capped", null, false, false);
        this.capped = capped;
    }

    public IngestCaps.Capped capped() {
        return capped;
    }
}
