package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code webanalytics.coverage.go-live-date} bindings (docs/100 §8.2, §11, rev 3.5): the shipped {@code application.yaml}
 * leaves it empty (no go-live yet), and WA-6 (homelab-data-service#61) sets it with
 * {@code WEBANALYTICS_COVERAGE_GOLIVEDATE} (the spelling {@code WEBANALYTICS_COVERAGE_GO_LIVE_DATE} binds too). The
 * test environment replaces the source named {@code systemEnvironment}, the only one Spring maps env names for.
 *
 * <p>WA-6 (homelab-data-service#61): the value the go-live PR puts into {@code k8s/deployment.yaml} is checked here
 * before Flux rolls it out: a quoted ISO date on the {@code data-service} container, no {@code valueFrom}, no other
 * {@code WEBANALYTICS_COVERAGE*} entry, and it binds to a date. Without the entry that test is <b>skipped (= no
 * go-live date set)</b>; in the go-live PR it must run (check the surefire report).
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

    @Test
    void theDashedPropertyBinds() throws IOException {
        StandardEnvironment environment = environment(Map.of());
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("webanalytics.coverage.go-live-date", "2027-01-15")));
        assertThat(bind(environment).coverage().goLiveDate()).isEqualTo(LocalDate.parse("2027-01-15"));
    }

    @Test
    void theManifestValueBindsToADate() throws IOException {
        String value = goLiveEnvValue(Files.readString(Path.of("k8s/deployment.yaml")));
        assumeTrue(value != null, "skipped = no go-live date set in k8s/deployment.yaml");
        LocalDate goLive = bind(environment(Map.of("WEBANALYTICS_COVERAGE_GOLIVEDATE", value))).coverage().goLiveDate();
        assertThat(goLive).isNotNull().isAfterOrEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test
    void theManifestCheckAcceptsAQuotedIsoDateAndRejectsEverythingElse() {
        String quoted = env("WEBANALYTICS_COVERAGE_GOLIVEDATE", "value: \"2026-11-20\"");
        assertThat(goLiveEnvValue(deployment("data-service", container("data-service", quoted)))).isEqualTo("2026-11-20");
        assertThat(goLiveEnvValue(deployment("data-service", container("data-service", "")))).isNull();
        // Unquoted, YAML turns the date into a timestamp: Kubernetes would reject it, so must the check.
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service", container("data-service",
                env("WEBANALYTICS_COVERAGE_GOLIVEDATE", "value: 2026-11-20"))))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service", container("data-service",
                env("WEBANALYTICS_COVERAGE_GOLIVEDATE", "valueFrom: {configMapKeyRef: {name: x, key: y}}")))))
                .isInstanceOf(AssertionError.class);
        // Another coverage variable, or the go-live entry twice (the last one would win silently).
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service", container("data-service",
                quoted + env("WEBANALYTICS_COVERAGE_GO_LIVE_DATE", "value: \"2026-11-20\"")))))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service", container("data-service", quoted + quoted))))
                .isInstanceOf(AssertionError.class);
        // On another container of the data-service Deployment it would be skipped instead of rejected.
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service",
                container("data-service", "") + container("sidecar", quoted)))).isInstanceOf(AssertionError.class);
        // The data-service Deployment must have its data-service container; other Deployments are ignored.
        assertThatThrownBy(() -> goLiveEnvValue(deployment("data-service", container("web", ""))))
                .isInstanceOf(AssertionError.class);
        assertThat(goLiveEnvValue(deployment("other", container("web", quoted)) + "---\n"
                + deployment("data-service", container("data-service", "")))).isNull();
    }

    private static String deployment(String name, String containers) {
        return "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: " + name + "\nspec:\n  template:\n"
                + "    spec:\n      containers:\n" + containers;
    }

    private static String container(String name, String envEntries) {
        return "        - name: " + name + "\n          env:\n"
                + "            - name: OTHER\n              value: \"x\"\n" + envEntries;
    }

    private static String env(String name, String attributes) {
        return "            - name: " + name + "\n              " + attributes + "\n";
    }

    /**
     * The value of {@code WEBANALYTICS_COVERAGE_GOLIVEDATE} on the {@code data-service} container of the
     * {@code data-service} Deployment, or null when there is none. Fails (AssertionError) when that Deployment lacks
     * its {@code data-service} container, for a coverage entry on another container, a second or another
     * {@code WEBANALYTICS_COVERAGE*} entry, {@code valueFrom}, or a non-string value. Other Deployments are ignored.
     */
    @SuppressWarnings("unchecked")
    static String goLiveEnvValue(String yaml) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yaml)) {
            if (!(document instanceof Map<?, ?> doc) || !"Deployment".equals(doc.get("kind"))
                    || !"data-service".equals(((Map<String, Object>) doc.get("metadata")).get("name"))) {
                continue;
            }
            Map<String, Object> podSpec = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    doc.get("spec")).get("template")).get("spec");
            List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
            assertThat(containers).as("the data-service Deployment has a data-service container")
                    .anyMatch(c -> "data-service".equals(c.get("name")));
            for (Map<String, Object> container : containers) {
                List<Map<String, Object>> env = container.get("env") == null ? List.of()
                        : (List<Map<String, Object>>) container.get("env");
                for (Map<String, Object> entry : env) {
                    if (String.valueOf(entry.get("name")).startsWith("WEBANALYTICS_COVERAGE")) {
                        assertThat(container.get("name")).as("coverage entries only on the data-service container")
                                .isEqualTo("data-service");
                        entries.add(entry);
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        assertThat(entries).as("exactly one WEBANALYTICS_COVERAGE_GOLIVEDATE and no other coverage entry")
                .singleElement().satisfies(e -> assertThat(e.get("name")).isEqualTo("WEBANALYTICS_COVERAGE_GOLIVEDATE"));
        Map<String, Object> entry = entries.getFirst();
        assertThat(entry).as("no valueFrom").doesNotContainKey("valueFrom");
        assertThat(entry.get("value")).as("a quoted ISO date (string)").isInstanceOf(String.class);
        return (String) entry.get("value");
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
