package ch.furchert.homelab.data.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdTokenRejectingValidatorTest {

    private final IdTokenRejectingValidator validator = new IdTokenRejectingValidator();

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("furchert-ch")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void acceptsAccessTokens() {
        // client_credentials tokens: scope, no role, no azp
        assertThat(validator.validate(token().claim("scope", List.of("netmon:read")).build()).hasErrors()).isFalse();
        // user access tokens carry role but never azp
        assertThat(validator.validate(token().subject("dominic").claim("role", "ADMIN").build()).hasErrors()).isFalse();
    }

    @Test
    void rejectsIdTokens() {
        OAuth2TokenValidatorResult result = validator.validate(token().claim("azp", "furchert-ch").build());
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).allSatisfy(e -> assertThat(e.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }
}
