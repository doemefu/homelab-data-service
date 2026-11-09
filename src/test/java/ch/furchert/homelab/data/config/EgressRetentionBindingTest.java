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
 * Egress retention settings (docs/060 §3.5, data-service#57): raw hourly rows 8 days, daily rows 32 days, the staging
 * override {@code NETMON_RETENTION_EGRESSFLOWSNAPSHOTSDAYS=30} (k8s/deployment.yaml) binds, and the daily retention must
 * exceed the raw one by at least 2 days.
 */
class EgressRetentionBindingTest {

    @Test
    void shippedDefaults() throws IOException {
        RetentionProperties properties = bind(environment(Map.of()));
        assertThat(properties.egressFlowSnapshotsDays()).isEqualTo(8);
        assertThat(properties.egressFlowDailyDays()).isEqualTo(32);
    }

    @Test
    void theStagingOverrideBinds() throws IOException {
        assertThat(bind(environment(Map.of("NETMON_RETENTION_EGRESSFLOWSNAPSHOTSDAYS", "30")))
                .egressFlowSnapshotsDays()).isEqualTo(30);
    }

    @Test
    void dailyMustExceedRawByTwoDaysAndRawNeedsTwoDays() {
        assertThatThrownBy(() -> bind(environment(Map.of("NETMON_RETENTION_EGRESSFLOWSNAPSHOTSDAYS", "31"))))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("egress-flow-daily-days");
        assertThatThrownBy(() -> bind(environment(Map.of("NETMON_RETENTION_EGRESSFLOWSNAPSHOTSDAYS", "1"))))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("egress-flow-snapshots-days");
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

    private static RetentionProperties bind(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("netmon.retention", RetentionProperties.class)
                .orElseGet(() -> Binder.get(environment).bindOrCreate("netmon.retention", RetentionProperties.class));
    }
}
