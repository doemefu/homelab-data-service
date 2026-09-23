package ch.furchert.homelab.data.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Grants access only to JWTs whose {@code sub} is in {@code netmon.api.allowed-clients}
 * (docs/060 §7.5). Combined with {@code SCOPE_netmon:read}, this rejects user tokens (their
 * {@code sub} is a username) even if a user flow ever obtained the scope.
 */
public final class AllowedClientAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final Set<String> allowedClients;

    public AllowedClientAuthorizationManager(Collection<String> allowedClients) {
        this.allowedClients = Set.copyOf(allowedClients);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        boolean granted = current instanceof JwtAuthenticationToken jwt
                && jwt.isAuthenticated()
                && jwt.getToken().getSubject() != null
                && allowedClients.contains(jwt.getToken().getSubject());
        return new AuthorizationDecision(granted);
    }
}
