package ch.furchert.homelab.data.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Pins the application clock at 2026-09-24T12:00Z for the egress read-path tests (data-service#57 plan review), so
 * "windows inside the raw retention read the hourly rows only" is proven against a fixed "now".
 */
@TestConfiguration
public class PinnedEgressClock {

    public static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Bean
    @Primary
    Clock pinnedEgressClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
