package ch.furchert.homelab.data.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudflarePropertiesTest {

    @Test
    void queryBudgetMustCoverOneSlicedHour() {
        assertThatThrownBy(() -> new CloudflareProperties("u", "t", "z", 5000, 1000, 20, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-queries-per-run must be >= 13");
        assertThat(new CloudflareProperties("u", "t", "z", 5000, 1000, 20, 13).maxQueriesPerRun()).isEqualTo(13);
    }

    @Test
    void pageSizesMustBePositive() {
        assertThatThrownBy(() -> new CloudflareProperties("u", "t", "z", 0, 1000, 20, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverPrintsTheToken() {
        assertThat(new CloudflareProperties("u", "secret-token", "z", 5000, 1000, 20, 60).toString())
                .doesNotContain("secret-token");
    }
}
