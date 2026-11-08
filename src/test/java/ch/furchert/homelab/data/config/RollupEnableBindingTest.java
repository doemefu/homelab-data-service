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
 * alternative {@code SPRING_APPLICATION_JSON} enables it as well.
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
    void anEnvironmentVariableBindsOnlyBecauseTheKeyExistsInTheYaml() throws IOException {
        // The binder derives env names from keys it already knows: with the application.yaml entry,
        // NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED maps to the dashed key; the form without a separator does not.
        assertThat(bind(environment(Map.of("NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED", "true"))).isEnabled(KEY))
                .isTrue();
        assertThat(bind(environment(Map.of("NETMON_COLLECTORS_WEBANALYTICSROLLUP_ENABLED", "true"))).isEnabled(KEY))
                .isFalse();
    }

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", env));
        return environment;
    }

    private static NetmonProperties bind(StandardEnvironment environment) {
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment).bind("netmon", NetmonProperties.class).get();
    }
}
