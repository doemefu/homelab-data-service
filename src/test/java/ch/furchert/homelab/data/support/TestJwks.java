package ch.furchert.homelab.data.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stand-in for auth-service in integration tests: an in-memory RSA key, a JWKS served by the
 * JDK's built-in HTTP server (no extra test dependency), and a token minter. Tokens are real
 * RS256 JWTs, so the full resource-server path (signature, exp, iss, converter, authorization)
 * is exercised exactly as in production. Keys exist only in memory for the test JVM.
 */
public final class TestJwks {

    public static final String ISSUER = "https://auth.test.local";
    private static final String KEY_ID = "test-key";

    private static final RSAKey SIGNING_KEY = generateKey(KEY_ID);
    /** A second key that is NOT published in the JWKS — tokens signed with it must be rejected. */
    private static final RSAKey FOREIGN_KEY = generateKey(KEY_ID);
    private static final HttpServer SERVER = startJwksServer();

    private TestJwks() {
    }

    public static String jwksUri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/oauth2/jwks";
    }

    /**
     * A token shaped like auth-service's client-credentials token for the furchert-ch client
     * (docs/060 §7.5): iss, sub=furchert-ch, aud=furchert-ch, scope=[netmon:read], exp=15 min.
     * The customizer may override any claim.
     */
    public static String token(Consumer<JwtClaimsSet.Builder> customizer) {
        return sign(SIGNING_KEY, customizer);
    }

    public static String tokenSignedWithForeignKey(Consumer<JwtClaimsSet.Builder> customizer) {
        return sign(FOREIGN_KEY, customizer);
    }

    public static String furchertChClientToken() {
        return token(claims -> {
        });
    }

    private static String sign(RSAKey key, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("furchert-ch")
                .audience(List.of("furchert-ch"))
                .claim("scope", List.of("netmon:read"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        customizer.accept(claims);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(keyId)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
