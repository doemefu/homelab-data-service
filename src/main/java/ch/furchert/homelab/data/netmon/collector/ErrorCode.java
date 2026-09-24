package ch.furchert.homelab.data.netmon.collector;

/** {@code lastErrorCode} values of the status API (docs/060 §7.2). */
public enum ErrorCode {
    CREDENTIALS("credentials"),
    RATE_LIMITED("rate_limited"),
    UPSTREAM("upstream"),
    TRUNCATED("truncated"),
    /** A run that stored what it could but skipped upstream rows violating the table contract (a warning). */
    PARTIAL("partial"),
    INTERNAL("internal");

    private final String value;

    ErrorCode(String value) {
        this.value = value;
    }

    /** The constant for a stored {@code last_error_code}, or {@code null} for null or an unknown value. */
    public static ErrorCode fromValue(String value) {
        for (ErrorCode code : values()) {
            if (code.value.equals(value)) {
                return code;
            }
        }
        return null;
    }

    /** The value stored in {@code collector_state.last_error_code} and returned by the API. */
    public String value() {
        return value;
    }
}
