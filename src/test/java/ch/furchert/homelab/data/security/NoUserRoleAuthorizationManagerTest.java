package ch.furchert.homelab.data.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NoUserRoleAuthorizationManagerTest {

    private final NoUserRoleAuthorizationManager manager = new NoUserRoleAuthorizationManager();

    private static Authentication jwtWith(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("furchert-ch")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("scope", "analytics:write")
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authorities));
    }

    private boolean granted(Authentication authentication) {
        return manager.authorize(() -> authentication, null).isGranted();
    }

    @Test
    void clientCredentialsTokenWithScopesOnlyIsGranted() {
        assertThat(granted(jwtWith("SCOPE_analytics:write"))).isTrue();
    }

    @Test
    void tokenWithAnyRoleIsDenied() {
        assertThat(granted(jwtWith("SCOPE_analytics:write", "ROLE_ADMIN"))).isFalse();
        assertThat(granted(jwtWith("SCOPE_analytics:write", "ROLE_USER"))).isFalse();
    }

    @Test
    void unauthenticatedIsDenied() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("furchert-ch", "n/a", "SCOPE_analytics:write");
        token.setAuthenticated(false);
        assertThat(granted(token)).isFalse();
    }

    @Test
    void anonymousIsDenied() {
        assertThat(granted(null)).isFalse();
    }
}
