package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServicePropertiesTest {

    @Test
    void pageSizeStaysWithinTheProducerLimit() {
        assertThatThrownBy(() -> new AuthServiceProperties("t", "u", "data-service", "s", 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthServiceProperties("t", "u", "data-service", "s", 1001, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 1000");
        assertThatThrownBy(() -> new AuthServiceProperties("t", "u", "data-service", "s", 500, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentialsNeedIdAndSecret() {
        assertThat(new AuthServiceProperties("t", "u", "data-service", "", 500, 10).hasCredentials()).isFalse();
        assertThat(new AuthServiceProperties("t", "u", "data-service", " ", 500, 10).hasCredentials()).isFalse();
        assertThat(new AuthServiceProperties("t", "u", "data-service", "s", 500, 10).hasCredentials()).isTrue();
    }

    @Test
    void loginEventsUrlIsDerivedFromTheBaseUrl() {
        assertThat(new AuthServiceProperties("t", "http://auth:8080/", "c", "s", 500, 10).loginEventsUrl())
                .isEqualTo("http://auth:8080/api/v1/login-events");
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        assertThat(new AuthServiceProperties("t", "u", "data-service", "very-secret", 500, 10).toString())
                .doesNotContain("very-secret");
    }
}
