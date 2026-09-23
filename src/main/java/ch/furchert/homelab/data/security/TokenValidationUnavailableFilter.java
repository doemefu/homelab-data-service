package ch.furchert.homelab.data.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * When a token cannot be validated for a server-side reason (e.g. auth-service's JWKS is
 * unreachable), the resource server rethrows {@link AuthenticationServiceException} instead of
 * answering 401. Without this filter the servlet container renders Boot's default JSON error; this
 * keeps the API's problem+json contract (500, {@code code=internal}, docs/060 §7.1).
 * Registered before {@code BearerTokenAuthenticationFilter} in the netmon API chain only.
 */
public final class TokenValidationUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenValidationUnavailableFilter.class);

    private final ProblemJsonWriter writer;

    public TokenValidationUnavailableFilter(JsonMapper jsonMapper) {
        this.writer = new ProblemJsonWriter(jsonMapper);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException e) {
            // The message names the JWKS URI at most; it never contains the token.
            log.warn("[auth] token validation unavailable: {}", e.getMessage());
            if (!response.isCommitted()) {
                writer.write(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "Token validation is temporarily unavailable");
            }
        }
    }
}
