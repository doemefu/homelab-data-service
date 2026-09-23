package ch.furchert.homelab.data.web;

import org.springframework.http.HttpStatus;

/**
 * A client error with a contract {@code code} (docs/060 §7.1), rendered as problem+json by
 * {@link ProblemDetailsAdvice}. The detail is fixed text written in code; it never echoes input.
 */
public class NetmonApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public NetmonApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static NetmonApiException invalidWindow() {
        return new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_WINDOW,
                "from and to must be ISO-8601 instants and to - from must be > 0 and <= 30 days");
    }

    public static NetmonApiException invalidParameter(String detail) {
        return new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER, detail);
    }

    public static NetmonApiException notFound(String detail) {
        return new NetmonApiException(HttpStatus.NOT_FOUND, ProblemCode.NOT_FOUND, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
