package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.lan.*} (docs/060 §4.1, §4.6).
 *
 * @param maxWindowsPerRun catch-up throttle: 15-minute windows processed per run (each costs five Prometheus
 *                         queries, six with a retry); the 48 h cap is 192 windows
 */
@ConfigurationProperties("netmon.lan")
public record LanProperties(@DefaultValue("32") int maxWindowsPerRun) {

    public LanProperties {
        if (maxWindowsPerRun < 1) {
            throw new IllegalArgumentException("netmon.lan.max-windows-per-run must be >= 1, was " + maxWindowsPerRun);
        }
    }
}
