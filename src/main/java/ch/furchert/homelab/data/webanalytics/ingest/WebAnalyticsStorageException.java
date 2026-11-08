package ch.furchert.homelab.data.webanalytics.ingest;

/**
 * A failed batch write, rendered as 500 {@code internal}. Deliberately carries no cause: database messages (e.g. a
 * CHECK violation's "failing row") contain event values incl. the visitor id and must never reach a log.
 */
public class WebAnalyticsStorageException extends RuntimeException {

    public WebAnalyticsStorageException() {
        super("Web analytics batch could not be stored");
    }
}
