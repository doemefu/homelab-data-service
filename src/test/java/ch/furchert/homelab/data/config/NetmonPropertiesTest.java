package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NetmonPropertiesTest {

    private static NetmonProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("netmon", NetmonProperties.class);
    }

    @Test
    void defaultsAllowFurchertChAndEnableEveryCollector() {
        NetmonProperties properties = bind(Map.of());

        assertThat(properties.api().allowedClients()).containsExactly("furchert-ch");
        assertThat(properties.isEnabled("retention")).isTrue();
    }

    @Test
    void dashedCollectorNamesBindAsKillSwitchKeys() {
        NetmonProperties properties = bind(Map.of("netmon.collectors.cloudflare-requests.enabled", "false"));

        assertThat(properties.isEnabled("cloudflare-requests")).isFalse();
        assertThat(properties.isEnabled("cloudflare-firewall")).isTrue();
    }

    @Test
    void allowlistIsConfigurable() {
        NetmonProperties properties = bind(Map.of(
                "netmon.api.allowed-clients[0]", "furchert-ch",
                "netmon.api.allowed-clients[1]", "other-client"));

        assertThat(properties.api().allowedClients()).containsExactly("furchert-ch", "other-client");
    }
}
