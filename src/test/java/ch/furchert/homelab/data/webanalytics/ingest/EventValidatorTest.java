package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.support.WebAnalyticsFixtures.Case;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Second-line validation (docs/100 §6.2, C13) over the shared fixtures plus explicit boundaries. */
class EventValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-08T15:00:00Z");

    private final EventValidator validator = new EventValidator();

    static List<Case> fixtures() {
        return WebAnalyticsFixtures.cases(NOW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureCase(Case fixture) {
        EventValidator.Result result = validator.validate(WebAnalyticsFixtures.toDto(fixture.event()), NOW);
        if (fixture.accepted()) {
            assertThat(result.isValid()).as(fixture.name()).isTrue();
        } else {
            assertThat(result.isValid()).as(fixture.name()).isFalse();
            assertThat(result.reason().label()).as(fixture.name()).isEqualTo(fixture.reason());
        }
    }

    @Test
    void fixturesCoverEveryTypeAndEveryWa1Reason() {
        List<Case> cases = fixtures();
        assertThat(cases.stream().filter(Case::accepted).map(c -> c.event().get("type")).distinct())
                .containsExactlyInAnyOrder("pageview", "engagement", "click", "goal", "enquiry");
        assertThat(cases.stream().map(Case::reason).filter(r -> r != null).distinct())
                .containsExactlyInAnyOrder("shape", "enum", "range", "time", "path");
    }

    @Test
    void validEventIsParsed() {
        Map<String, Object> event = WebAnalyticsFixtures.event("pageview", NOW);
        ValidEvent valid = validator.validate(WebAnalyticsFixtures.toDto(event), NOW).event();
        assertThat(valid.eventId().toString()).isEqualTo(event.get("eventId"));
        assertThat(valid.occurredAt()).isEqualTo(NOW.minus(Duration.ofMinutes(2)));
        assertThat(valid.isEnquiry()).isFalse();
    }

    @Test
    void receivedAtWindowIsInclusive() {
        assertThat(timeResult(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(2)))).isNull();
        assertThat(timeResult(NOW.minus(Duration.ofHours(2)).minusMillis(1), NOW.minus(Duration.ofHours(2)).minusMillis(1)))
                .isEqualTo(RejectReason.TIME);
        assertThat(timeResult(NOW.plus(Duration.ofMinutes(1)), NOW.plus(Duration.ofMinutes(1)))).isNull();
        assertThat(timeResult(NOW.plus(Duration.ofMinutes(1)).plusMillis(1), NOW.plus(Duration.ofMinutes(1)).plusMillis(1)))
                .isEqualTo(RejectReason.TIME);
    }

    @Test
    void occurredAtSkewIsInclusive() {
        assertThat(timeResult(NOW.minus(Duration.ofMinutes(15)), NOW)).isNull();
        assertThat(timeResult(NOW.minus(Duration.ofMinutes(15)).minusMillis(1), NOW)).isEqualTo(RejectReason.TIME);
        assertThat(timeResult(NOW.plusMillis(1), NOW)).isEqualTo(RejectReason.TIME);
    }

    @Test
    void impossibleDateIsATimeRejectNotAnException() {
        Map<String, Object> event = new HashMap<>(WebAnalyticsFixtures.event("pageview", NOW));
        event.put("occurredAt", "2026-13-45T25:61:00Z");
        assertThat(validator.validate(WebAnalyticsFixtures.toDto(event), NOW).reason()).isEqualTo(RejectReason.TIME);
    }

    @Test
    void pathSegmentBoundaries() {
        String segment = "/" + "a".repeat(64);
        String path = segment + segment + segment; // 195 characters, three segments: valid
        assertThat(EventValidator.isCanonicalPath(path)).isTrue();
        assertThat(EventValidator.isCanonicalPath("/" + "a".repeat(65))).isFalse();
    }

    private RejectReason timeResult(Instant occurredAt, Instant receivedAt) {
        Map<String, Object> event = new HashMap<>(WebAnalyticsFixtures.event("enquiry", NOW));
        event.put("occurredAt", occurredAt.toString());
        event.put("receivedAt", receivedAt.toString());
        return validator.validate(WebAnalyticsFixtures.toDto(event), NOW).reason();
    }
}
