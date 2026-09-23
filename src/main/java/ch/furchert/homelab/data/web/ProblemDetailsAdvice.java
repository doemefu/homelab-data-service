package ch.furchert.homelab.data.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

/**
 * Renders every MVC error as RFC 9457 {@code application/problem+json} with the contract's
 * {@code code} member (docs/060 §7.1, §7.4). {@code detail} never carries a token or a stack trace;
 * 5xx details are replaced by a fixed text.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        enrich(problem, statusCode, request);
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private static void enrich(ProblemDetail problem, HttpStatusCode status, WebRequest request) {
        problem.setType(URI.create("about:blank"));
        if (status.is5xxServerError()) {
            problem.setDetail("Internal server error");
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", ProblemCode.forStatus(status));
        }
        if (request instanceof NativeWebRequest nativeRequest
                && nativeRequest.getNativeRequest(HttpServletRequest.class) instanceof HttpServletRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequestURI()));
        }
    }
}
