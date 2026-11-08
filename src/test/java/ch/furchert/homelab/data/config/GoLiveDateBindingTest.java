package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code webanalytics.coverage.go-live-date} bindings (docs/100 §8.2, §11, rev 3.5): the shipped {@code application.yaml}
 * leaves it empty (no go-live yet), and WA-6 (homelab-data-service#61) sets it with
 * {@code WEBANALYTICS_COVERAGE_GOLIVEDATE} (the spelling {@code WEBANALYTICS_COVERAGE_GO_LIVE_DATE} binds too). The
 * test environment replaces the source named {@code systemEnvironment}, the only one Spring maps env names for.
 */
class GoLiveDateBindingTest {

    @Test
    void shippedConfigurationLeavesTheGoLiveDateUnset() throws IOException {
        assertThat(bind(environment(Map.of())).coverage().goLiveDate()).isNull();
    }

    @Test
    void theDocumentedEnvironmentVariableBinds() throws IOException {
        assertThat(bind(environment(Map.of("WEBANALYTICS_COVERAGE_GOLIVEDATE", "2027-01-15"))).coverage().goLiveDate())
                .isEqualTo(LocalDate.parse("2027-01-15"));
    }

    @Test
    void theSpellingWithUnderscoresInsideTheNameBindsAsWell() throws IOException {
        // Documented for completeness: relaxed binding also accepts this form; the spec names GOLIVEDATE.
        assertThat(bind(environment(Map.of("WEBANALYTICS_COVERAGE_GO_LIVE_DATE", "2027-01-15"))).coverage()
                .goLiveDate()).isEqualTo(LocalDate.parse("2027-01-15"));
    }

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        // Replace the real OS environment with the test map under the same name: Spring applies its environment
        // variable name mapping only to the source named "systemEnvironment".
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        return environment;
    }

    private static WebAnalyticsProperties bind(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("webanalytics", WebAnalyticsProperties.class).get();
    }
}
