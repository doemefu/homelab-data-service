package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate N1 bindings (docs/100 §7.5; homelab#350 plan review): the shipped {@code application.yaml} disables the
 * rollup explicitly (a collector without an entry would be enabled); the documented enable path
 * {@code NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED=true} binds only because that YAML entry exists; the
 * alternative {@code SPRING_APPLICATION_JSON} enables it as well. The test environment replaces the source named
 * {@code systemEnvironment}, the only one Spring applies its environment-variable name mapping to.
 */
class RollupEnableBindingTest {

    private static final String KEY = "webanalytics-rollup";

    @Test
    void shippedConfigurationDisablesTheRollup() throws IOException {
        assertThat(bind(environment(Map.of())).isEnabled(KEY)).isFalse();
        assertThat(bind(environment(Map.of())).collectors()).containsKey(KEY);
    }

    @Test
    void springApplicationJsonEnablesIt() throws IOException {
        StandardEnvironment env = environment(Map.of("SPRING_APPLICATION_JSON",
                "{\"netmon\":{\"collectors\":{\"webanalytics-rollup\":{\"enabled\":true}}}}"));
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(bind(env).isEnabled(KEY)).isTrue();
    }

    @Test
    void theEnvironmentVariableBindsBecauseTheKeyExistsInTheYaml() throws IOException {
        // The binder derives env names from map keys it already knows: with the application.yaml entry, the documented
        // NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED (and the form without a separator) address the dashed key.
        assertThat(bind(environment(Map.of("NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED", "true"))).isEnabled(KEY))
                .isTrue();
        assertThat(bind(environment(Map.of("NETMON_COLLECTORS_WEBANALYTICSROLLUP_ENABLED", "true"))).isEnabled(KEY))
                .isTrue();
    }

    @Test
    void withoutTheYamlEntryNoEnvironmentVariableReachesTheDashedKey() {
        // Why the explicit YAML entry is mandatory: without it neither spelling can switch the rollup off (it binds a
        // different key or nothing), and the collector would run by default.
        for (String name : new String[]{"NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED",
                "NETMON_COLLECTORS_WEBANALYTICSROLLUP_ENABLED"}) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(name, "false")));
            ConfigurationPropertySources.attach(environment);
            NetmonProperties properties = Binder.get(environment).bind("netmon", NetmonProperties.class)
                    .orElseGet(() -> new NetmonProperties(null, null));
            assertThat(properties.collectors()).as(name).doesNotContainKey(KEY);
            assertThat(properties.isEnabled(KEY)).as(name).isTrue();
        }
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

    private static NetmonProperties bind(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("netmon", NetmonProperties.class).get();
    }
}
