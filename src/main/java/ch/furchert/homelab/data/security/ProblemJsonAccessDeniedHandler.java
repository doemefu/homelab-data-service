package ch.furchert.homelab.data.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** 403 with the standard {@code WWW-Authenticate: Bearer error="insufficient_scope"} header plus a problem+json body. */
public final class ProblemJsonAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
    private final ProblemJsonWriter writer;
    private final String detail;

    public ProblemJsonAccessDeniedHandler(JsonMapper jsonMapper) {
        this(jsonMapper, "The token is not allowed to read netmon data");
    }

    /** @param detail fixed text for the problem {@code detail}; never derived from the request or the token */
    public ProblemJsonAccessDeniedHandler(JsonMapper jsonMapper, String detail) {
        this.writer = new ProblemJsonWriter(jsonMapper);
        this.detail = detail;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        bearer.handle(request, response, accessDeniedException);
        writer.write(request, response, HttpStatus.FORBIDDEN, detail);
    }
}
