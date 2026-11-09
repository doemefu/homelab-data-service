package ch.furchert.homelab.data.webanalytics.ingest;

/** An erasure ran out of its per-request bound; deleted batches are committed and a retry finishes it (503). */
public class VisitorEraseIncompleteException extends RuntimeException {

    public VisitorEraseIncompleteException() {
        super("visitor erase incomplete", null, false, false);
    }
}
