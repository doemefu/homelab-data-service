package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * {@code netmon.*} settings (docs/060 §4.1, §7.5).
 *
 * @param api        read-API authorization settings
 * @param collectors per-collector kill switches, keyed by collector name ({@code netmon.collectors.<name>.enabled})
 */
@ConfigurationProperties("netmon")
public record NetmonProperties(@DefaultValue Api api, Map<String, Collector> collectors) {

    public NetmonProperties {
        collectors = collectors == null ? Map.of() : Map.copyOf(collectors);
    }

    /** A collector without an explicit entry is enabled (docs/060 §4.1 default {@code true}). */
    public boolean isEnabled(String collector) {
        Collector toggle = collectors.get(collector);
        return toggle == null || toggle.enabled();
    }

    /**
     * @param allowedClients token {@code sub} values allowed to call {@code /api/netmon/**} (v1: {@code furchert-ch})
     */
    public record Api(@DefaultValue("furchert-ch") List<String> allowedClients) {

        public Api {
            allowedClients = allowedClients == null ? List.of() : List.copyOf(allowedClients);
        }
    }

    public record Collector(@DefaultValue("true") boolean enabled) {
    }
}
