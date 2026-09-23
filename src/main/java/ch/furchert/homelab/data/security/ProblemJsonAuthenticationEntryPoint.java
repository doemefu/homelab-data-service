package ch.furchert.homelab.data.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** 401 with the standard {@code WWW-Authenticate: Bearer ...} header plus a problem+json body. */
public final class ProblemJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ProblemJsonWriter writer;

    public ProblemJsonAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.writer = new ProblemJsonWriter(jsonMapper);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        bearer.commence(request, response, authException);
        writer.write(request, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
    }
}
