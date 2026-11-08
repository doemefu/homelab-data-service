package ch.furchert.homelab.data;

import ch.furchert.homelab.data.support.TestJwks;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WA-1 (#53) upgrade and rollback: the app starts on a database that is at V5 (Boot's Flyway applies V6), and an
 * older image that only knows V1 to V5 still validates and migrates a V6 database, because Flyway's default
 * {@code ignore-migration-patterns} is {@code *:future} (docs/100 §12.4). Own context and database in the shared
 * container; package {@code ch.furchert.homelab.data} for access to it.
 */
@SpringBootTest
class WebAnalyticsUpgradeIntegrationTest {

    private static final String DATABASE = "upgrade_from_v5";
    private static final Pattern VERSION = Pattern.compile("^V([0-9]+)__.*\\.sql$");

    @DynamicPropertySource
    static void databaseAtV5(DynamicPropertyRegistry registry) {
        PostgresAccess.recreateDatabase(DATABASE);
        String url = PostgresAccess.jdbcUrl(DATABASE);
        Flyway.configure()
                .dataSource(url, PostgresAccess.user(), PostgresAccess.password())
                .table("flyway_schema_history_data").defaultSchema("public").schemas("public", "netmon")
                .locations("classpath:db/migration").target("5")
                .load().migrate();
        PostgresAccess.execute(DATABASE, "INSERT INTO netmon.collector_state (collector) VALUES ('upgrade-marker')");

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", PostgresAccess::user);
        registry.add("spring.datasource.password", PostgresAccess::password);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwks::jwksUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwks.ISSUER);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void appStartsOnV5DatabaseAndAnOlderImageStillStartsOnV6() throws IOException {
        // 1. The context started against the V5 database: V6 applied, netmon data intact.
        assertThat(jdbc.sql("""
                        SELECT success FROM public.flyway_schema_history_data
                        WHERE version = '6' AND description = 'webanalytics ingest'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM netmon.collector_state WHERE collector = 'upgrade-marker'")
                .query(Long.class).single()).isEqualTo(1L);
        // V6 (WA-1: 3 tables) and V7 (WA-3a: 9 tables) are both applied on top of V5.
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'webanalytics'")
                .query(Long.class).single()).isEqualTo(12L);

        // 2. Rollback: an image before WA-1 (V1 to V5) and the WA-1 image (V1 to V6) against the newest database.
        olderImageStarts(5, "public", "netmon");
        olderImageStarts(6, "public", "netmon", "webanalytics");
    }

    private static void olderImageStarts(int newestVersion, String... schemas) throws IOException {
        Path previousImage = copyMigrationsUpTo(newestVersion);
        Flyway older = Flyway.configure()
                .dataSource(PostgresAccess.jdbcUrl(DATABASE), PostgresAccess.user(), PostgresAccess.password())
                .table("flyway_schema_history_data").defaultSchema("public").schemas(schemas)
                .locations("filesystem:" + previousImage)
                .load();
        older.validate();
        MigrateResult result = older.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();
    }

    private static Path copyMigrationsUpTo(int maxVersion) throws IOException {
        Path dir = Files.createTempDirectory("previous-image-migrations");
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
        int copied = 0;
        for (Resource script : scripts) {
            Matcher m = VERSION.matcher(script.getFilename());
            if (m.matches() && Integer.parseInt(m.group(1)) <= maxVersion) {
                try (InputStream in = script.getInputStream()) {
                    Files.copy(in, dir.resolve(script.getFilename()));
                }
                copied++;
            }
        }
        assertThat(copied).isEqualTo(maxVersion);
        return dir;
    }

    /** JDBC access to the shared container outside a Spring context. */
    static final class PostgresAccess {

        private PostgresAccess() {
        }

        static String user() {
            return AbstractIntegrationTest.postgres.getUsername();
        }

        static String password() {
            return AbstractIntegrationTest.postgres.getPassword();
        }

        static String jdbcUrl(String database) {
            return "jdbc:postgresql://" + AbstractIntegrationTest.postgres.getHost() + ":"
                    + AbstractIntegrationTest.postgres.getMappedPort(5432) + "/" + database;
        }

        static void recreateDatabase(String database) {
            execute("data_service", "DROP DATABASE IF EXISTS " + database);
            execute("data_service", "CREATE DATABASE " + database);
        }

        /** Statements are fixed test literals. */
        static void execute(String database, String sql) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(database), user(), password());
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new UncheckedIOException(new IOException(e));
            }
        }
    }
}
