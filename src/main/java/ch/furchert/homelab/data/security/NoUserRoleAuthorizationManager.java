package ch.furchert.homelab.data.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Denies any authentication that holds a {@code ROLE_*} authority (docs/100 §6.1). auth-service adds the
 * {@code role} claim only to user-driven tokens, never to client-credentials tokens, so this is defence in
 * depth against role-bearing user tokens: for example a user whose username equals a registered client id,
 * or a user token that obtained a service scope through an authorization-code flow. It is not a positive
 * client-credentials check (audience-bound user tokens and tokens of deleted users carry no role either);
 * that check belongs to auth-service (doemefu/homelab-auth-service#143).
 */
public final class NoUserRoleAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        boolean granted = current != null
                && current.isAuthenticated()
                && current.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .noneMatch(authority -> authority != null && authority.startsWith(ROLE_PREFIX));
        return new AuthorizationDecision(granted);
    }
}
