package ch.furchert.homelab.data.web;

import org.springframework.http.HttpStatusCode;

/** The {@code code} member of every problem+json error (docs/060 §7.1). */
public final class ProblemCode {

    public static final String INVALID_WINDOW = "invalid_window";
    public static final String INVALID_PARAMETER = "invalid_parameter";
    public static final String NOT_FOUND = "not_found";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String FORBIDDEN = "forbidden";
    public static final String INTERNAL = "internal";

    private ProblemCode() {
    }

    /**
     * Default code for a status. Other 4xx statuses (405, 406, 415, ...) map to
     * {@code invalid_parameter}, the closest contract value for a malformed request.
     */
    public static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            default -> status.is5xxServerError() ? INTERNAL : INVALID_PARAMETER;
        };
    }
}
