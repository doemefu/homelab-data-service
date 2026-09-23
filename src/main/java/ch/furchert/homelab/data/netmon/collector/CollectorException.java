package ch.furchert.homelab.data.netmon.collector;

/**
 * An expected collector failure. The message is persisted in {@code collector_state.last_error},
 * so it must be written by the collector itself and must never contain a URL with a query string,
 * a header value, a token or an IP address (docs/060 §3.3, §10).
 */
public class CollectorException extends RuntimeException {

    private final ErrorCode code;

    public CollectorException(ErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
