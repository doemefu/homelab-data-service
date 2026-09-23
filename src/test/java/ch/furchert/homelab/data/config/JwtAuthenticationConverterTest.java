package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The auth-service converter pattern (docs/060 §7.5): SCOPE_ and ROLE_ authorities are merged. */
class JwtAuthenticationConverterTest {

    private final JwtAuthenticationConverter converter = SecurityConfig.jwtAuthenticationConverter();

    /** Spring Security 7 adds a FACTOR_BEARER authority; only the converter's own output matters here. */
    private static List<String> projectAuthorities(AbstractAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_") || a.startsWith("SCOPE_"))
                .toList();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("furchert-ch");
    }

    @Test
    void clientCredentialsTokenKeepsScopeAuthorities() {
        AbstractAuthenticationToken auth = converter.convert(jwt().claim("scope", List.of("netmon:read")).build());
        assertThat(projectAuthorities(auth)).containsExactly("SCOPE_netmon:read");
    }

    @Test
    void userTokenGetsRoleAndScopes() {
        AbstractAuthenticationToken auth = converter.convert(jwt()
                .claim("role", "ADMIN")
                .claim("scope", "openid profile")
                .build());
        assertThat(projectAuthorities(auth)).containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile", "ROLE_ADMIN");
    }

    @Test
    void tokenWithoutScopeOrRoleHasNoProjectAuthorities() {
        AbstractAuthenticationToken auth = converter.convert(jwt().build());
        assertThat(projectAuthorities(auth)).isEmpty();
    }
}
