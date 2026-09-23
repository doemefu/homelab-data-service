package ch.furchert.homelab.data.netmon.collector;

/** {@code lastErrorCode} values of the status API (docs/060 §7.2). */
public enum ErrorCode {
    CREDENTIALS("credentials"),
    RATE_LIMITED("rate_limited"),
    UPSTREAM("upstream"),
    TRUNCATED("truncated"),
    INTERNAL("internal");

    private final String value;

    ErrorCode(String value) {
        this.value = value;
    }

    /** The value stored in {@code collector_state.last_error_code} and returned by the API. */
    public String value() {
        return value;
    }
}
