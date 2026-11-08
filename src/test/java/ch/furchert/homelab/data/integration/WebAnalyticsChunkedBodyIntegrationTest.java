package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.TestJwks;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counted body stream on a real Tomcat (docs/100 §6.2): a chunked body without Content-Length is cut at 64 KiB.
 * Standalone context (RANDOM_PORT) sharing the base class's container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebAnalyticsChunkedBodyIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AbstractIntegrationTest.registerDatasource(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwks::jwksUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwks.ISSUER);
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void chunkedBodyOverTheCapIs413() throws Exception {
        byte[] body = new byte[70_000];
        java.util.Arrays.fill(body, (byte) ' ');
        assertThat(postChunked(body).statusCode()).isEqualTo(413);
    }

    @Test
    void chunkedValidBatchIs200() throws Exception {
        String batch = WebAnalyticsFixtures.batch(List.of(WebAnalyticsFixtures.event("click", Instant.now())));
        HttpResponse<String> response = postChunked(batch.getBytes(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.body()).contains("\"accepted\":1");
    }

    /** {@code ofInputStream} has no known length, so the client sends {@code Transfer-Encoding: chunked}. */
    private HttpResponse<String> postChunked(byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + WebAnalyticsFixtures.EVENTS))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + WebAnalyticsFixtures.writeToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
