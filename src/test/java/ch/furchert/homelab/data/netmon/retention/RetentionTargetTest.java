package ch.furchert.homelab.data.netmon.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void netmonIsTheDefaultSchemaWithTheDefaultBatchSize() {
        RetentionTarget target = new RetentionTarget("inbound_request_groups", "window_start", 90);
        assertThat(target.schema()).isEqualTo("netmon");
        assertThat(target.batchSize()).isEqualTo(RetentionTarget.DEFAULT_BATCH_SIZE);
        assertThat(target.qualifiedName()).isEqualTo("inbound_request_groups");
    }

    @Test
    void theWebanalyticsSchemaIsAccepted() {
        RetentionTarget target = new RetentionTarget("events", "occurred_at", 396, null, "webanalytics", 2_000);
        assertThat(target.schema()).isEqualTo("webanalytics");
        assertThat(target.batchSize()).isEqualTo(2_000);
        assertThat(target.qualifiedName()).isEqualTo("webanalytics.events");
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "pg_catalog", "netmon; DROP", "Netmon", ""})
    void otherSchemasAreRejected(String schema) {
        assertThatThrownBy(() -> new RetentionTarget("events", "occurred_at", 396, null, schema, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchSizeBelowOneIsRejected() {
        assertThatThrownBy(() -> new RetentionTarget("events", "occurred_at", 396, null, "webanalytics", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
