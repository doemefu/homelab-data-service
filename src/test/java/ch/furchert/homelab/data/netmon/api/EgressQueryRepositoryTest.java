package ch.furchert.homelab.data.netmon.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The scope filter is fixed SQL text, so every plan can use the partial index on external rows (V10). */
class EgressQueryRepositoryTest {

    @Test
    void externalIsALiteralPredicateAndNullMeansAllScopes() {
        assertThat(EgressQueryRepository.scopeClause("external", "s")).isEqualTo("AND s.destination_scope = 'external'");
        assertThat(EgressQueryRepository.scopeClause(null, "d")).isEmpty();
        assertThatThrownBy(() -> EgressQueryRepository.scopeClause("pod'; --", "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
