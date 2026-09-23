package ch.furchert.homelab.data.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedClientAuthorizationManagerTest {

    private final AllowedClientAuthorizationManager manager = new AllowedClientAuthorizationManager(List.of("furchert-ch"));

    private static Authentication jwtFor(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        }
        return new JwtAuthenticationToken(builder.claim("scope", "netmon:read").build(), List.of());
    }

    private boolean granted(Authentication authentication) {
        return manager.authorize(() -> authentication, null).isGranted();
    }

    @Test
    void allowedSubjectIsGranted() {
        assertThat(granted(jwtFor("furchert-ch"))).isTrue();
    }

    @Test
    void otherSubjectIsDenied() {
        assertThat(granted(jwtFor("grafana"))).isFalse();
    }

    @Test
    void missingSubjectIsDenied() {
        assertThat(granted(jwtFor(null))).isFalse();
    }

    @Test
    void nonJwtAuthenticationIsDenied() {
        assertThat(granted(new TestingAuthenticationToken("furchert-ch", "n/a", "SCOPE_netmon:read"))).isFalse();
    }

    @Test
    void anonymousIsDenied() {
        assertThat(granted(null)).isFalse();
    }

    @Test
    void emptyAllowlistDeniesEverything() {
        AllowedClientAuthorizationManager none = new AllowedClientAuthorizationManager(List.of());
        assertThat(none.authorize(() -> jwtFor("furchert-ch"), null).isGranted()).isFalse();
    }
}
