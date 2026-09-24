package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code netmon.egress.*} validation (docs/060 §3.3, §4.6). */
class EgressPropertiesTest {

    @Test
    void acceptsTheDefaults() {
        EgressProperties properties = new EgressProperties("10.42.0.0/16", "10.43.0.0/16", "192.168.1.0/24", 12, 2000);
        assertThat(properties.pod().toString()).isEqualTo("10.42.0.0/16");
        assertThat(properties.lan().prefix()).isEqualTo(24);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new EgressProperties("10.42.0.0/33", "10.43.0.0/16", "192.168.1.0/24", 12, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgressProperties("10.42.0.0/16", "not-a-cidr", "192.168.1.0/24", 12, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgressProperties("10.42.0.0/16", "10.43.0.0/16", "192.168.1.0/24", 0, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgressProperties("10.42.0.0/16", "10.43.0.0/16", "192.168.1.0/24", 12, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
