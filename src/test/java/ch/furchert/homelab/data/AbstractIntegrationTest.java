package ch.furchert.homelab.data;

import ch.furchert.homelab.data.support.TestJwks;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // Static initializer keeps the container alive for the entire JVM lifetime.
    // Do NOT use @Testcontainers + @Container here: JUnit 5's AfterAllCallback
    // stops @Container static fields after each concrete subclass finishes, which
    // kills the container before the next integration test class can connect.
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("data_service")
            .withUsername("data_service")
            .withPassword("test-only-password");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registerDatasource(registry);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwks::jwksUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwks.ISSUER);
    }

    /** For test classes that need their own security properties but the shared database. */
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
