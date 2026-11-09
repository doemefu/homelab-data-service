package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WA-5 settings (docs/100 §6.4, §7.7, §11): the shipped defaults, the env names of the runbook "adjust caps"
 * ({@code WEBANALYTICS_INGEST_DAILYCAP}, {@code …_HOURLYCAP}, {@code …_VISITORDAILYCAP},
 * {@code WEBANALYTICS_RETENTION_EVENTSDAYS}), caps of 0 (data-side stop of analytics ingest) and the startup failure for
 * a retention below 370 days.
 */
class WebAnalyticsCapsBindingTest {

    @Test
    void shippedDefaults() throws IOException {
        WebAnalyticsProperties properties = bind(environment(Map.of()));
        assertThat(properties.ingest().dailyCap()).isEqualTo(10_000);
        assertThat(properties.ingest().hourlyCap()).isEqualTo(2_500);
        assertThat(properties.ingest().visitorDailyCap()).isEqualTo(500);
        assertThat(properties.retention().eventsDays()).isEqualTo(396);
    }

    @Test
    void theRunbookEnvironmentVariablesBind() throws IOException {
        WebAnalyticsProperties properties = bind(environment(Map.of("WEBANALYTICS_INGEST_DAILYCAP", "0",
                "WEBANALYTICS_INGEST_HOURLYCAP", "100", "WEBANALYTICS_INGEST_VISITORDAILYCAP", "50",
                "WEBANALYTICS_RETENTION_EVENTSDAYS", "400")));
        assertThat(properties.ingest().dailyCap()).isZero();
        assertThat(properties.ingest().hourlyCap()).isEqualTo(100);
        assertThat(properties.ingest().visitorDailyCap()).isEqualTo(50);
        assertThat(properties.retention().eventsDays()).isEqualTo(400);
    }

    @Test
    void aRetentionBelow370DaysFailsTheBinding() {
        assertThatThrownBy(() -> bind(environment(Map.of("WEBANALYTICS_RETENTION_EVENTSDAYS", "369"))))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("webanalytics.retention.events-days");
        assertThatThrownBy(() -> new WebAnalyticsProperties.Retention(369)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCapsAreRejected() {
        assertThatThrownBy(() -> new WebAnalyticsProperties.Ingest(-1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebAnalyticsProperties.Ingest(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebAnalyticsProperties.Ingest(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        return environment;
    }

    private static WebAnalyticsProperties bind(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("webanalytics", WebAnalyticsProperties.class).get();
    }
}
