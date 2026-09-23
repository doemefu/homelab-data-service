package ch.furchert.homelab.data.netmon.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** docs/060 §3.5: retention values below 1 are rejected at startup. */
class RetentionTargetTest {

    @Test
    void validTargetIsAccepted() {
        assertThatCode(() -> new RetentionTarget("inbound_request_groups", "window_start", 90)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void daysBelowOneAreRejected(int days) {
        assertThatThrownBy(() -> new RetentionTarget("inbound_request_groups", "window_start", days))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inbound_request_groups");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Inbound", "x; DROP TABLE y", "netmon.inbound", "1abc"})
    void nonIdentifierTableNamesAreRejected(String table) {
        assertThatThrownBy(() -> new RetentionTarget(table, "window_start", 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonIdentifierColumnNamesAreRejected() {
        assertThatThrownBy(() -> new RetentionTarget("inbound_request_groups", "window_start OR 1=1", 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
