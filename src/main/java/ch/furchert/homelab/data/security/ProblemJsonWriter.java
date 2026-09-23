package ch.furchert.homelab.data.security;

import ch.furchert.homelab.data.web.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the RFC 9457 body for 401/403 responses produced by the security filter chain, which runs
 * before Spring MVC and therefore cannot use {@link ch.furchert.homelab.data.web.ProblemDetailsAdvice}.
 * Only fixed texts are written — never the exception message, which may quote token contents.
 */
final class ProblemJsonWriter {

    private final JsonMapper jsonMapper;

    ProblemJsonWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", ProblemCode.forStatus(status));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
