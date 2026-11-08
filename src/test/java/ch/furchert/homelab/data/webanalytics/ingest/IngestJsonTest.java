package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.support.WebAnalyticsFixtures;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.exc.StreamConstraintsException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The strict ingest parser (docs/100 §6.2, C13; plan review F1, F10). */
class IngestJsonTest {

    private final IngestJson json = new IngestJson();

    /** The §6.2 example body verbatim, with fresh instants and full-length ids. */
    static String specExample(Instant now) {
        String occurred = now.minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS).toString();
        String received = now.truncatedTo(ChronoUnit.MILLIS).toString();
        return """
                { "events": [ { "eventId": "%s", "type": "pageview", "occurredAt": "%s", "receivedAt": "%s",
                                "visitorId": "%s", "pageviewId": "%s", "seq": 0, "path": "/automation", "locale": "de",
                                "landing": true, "referrerDomain": "google.com", "utmSource": "linkedin", "utmMedium": "social", "utmCampaign": "2026-11-ki",
                                "country": "CH", "deviceClass": "desktop", "browserFamily": "firefox", "osFamily": "macos", "viewport": "1024-1439",
                                "engagedMs": null, "scrollMax": null, "fits": null, "sectionMask": null, "formStarted": null,
                                "clickKind": null, "clickTarget": null, "clickArea": null, "goal": null } ],
                  "edgeDrops": { "bot": 0, "signed_in": 0, "consent": 0, "invalid": 0, "path": 0, "rate_limited": 0, "mint_limited": 0, "overflow": 0, "expired": 0 } }
                """.formatted(java.util.UUID.randomUUID(), occurred, received, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID());
    }

    @Test
    void specExampleParses() {
        IngestRequest request = json.parse(bytes(specExample(Instant.now())));
        assertThat(request.events()).hasSize(1);
        assertThat(request.events().getFirst().path()).isEqualTo("/automation");
        assertThat(request.edgeDrops().byReason()).containsOnlyKeys("bot", "signed_in", "consent", "invalid", "path",
                "rate_limited", "mint_limited", "overflow", "expired");
    }

    @Test
    void nestingDeeperThanEightFailsInTheParser() {
        // Binding stops at the first unexpected token, so drive the configured parser directly.
        String deep = "{\"events\": " + "[".repeat(8) + "]".repeat(8) + "}";
        assertThatThrownBy(() -> readAllTokens(deep)).isInstanceOf(StreamConstraintsException.class);
        readAllTokens("{\"events\": " + "[".repeat(7) + "]".repeat(7) + "}"); // depth 8 is allowed
        String body = "{\"events\": " + "[".repeat(9) + "]".repeat(9) + ", \"edgeDrops\": null}";
        assertThatThrownBy(() -> json.parse(bytes(body))).isInstanceOf(JacksonException.class);
    }

    private void readAllTokens(String body) {
        try (JsonParser parser = json.mapper().createParser(bytes(body))) {
            while (parser.nextToken() != null) {
                // consume
            }
        }
    }

    @Test
    void overLongStringFailsInTheParser() {
        String body = "{\"events\": [], \"edgeDrops\": \"" + "a".repeat(20_000) + "\"}";
        assertThatThrownBy(() -> json.parse(bytes(body))).isInstanceOf(StreamConstraintsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"eventId\": 123",
            "\"seq\": \"1\"",
            "\"seq\": 1.0",
            "\"seq\": true",
            "\"landing\": 1",
            "\"landing\": \"true\"",
            "\"path\": 5",
            "\"path\": false",
            "\"path\": [\"/automation\"]"
    })
    void scalarCoercionFails(String member) {
        String key = member.substring(1, member.indexOf('"', 1));
        Map<String, Object> event = new LinkedHashMap<>(WebAnalyticsFixtures.event("pageview", Instant.now()));
        event.put(key, "__PLACEHOLDER__");
        String body = WebAnalyticsFixtures.batch(List.of(event))
                .replace("\"" + key + "\":\"__PLACEHOLDER__\"", member);
        assertThat(body).contains(member);
        assertThatThrownBy(() -> json.parse(bytes(body))).isInstanceOf(JacksonException.class);
    }

    @Test
    void unknownEventKeyFails() {
        Map<String, Object> event = new LinkedHashMap<>(WebAnalyticsFixtures.event("click", Instant.now()));
        event.put("ip", "203.0.113.7");
        assertThatThrownBy(() -> json.parse(bytes(WebAnalyticsFixtures.batch(List.of(event)))))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void unknownTopLevelAndEdgeDropKeysFail() {
        String top = WebAnalyticsFixtures.batch(List.of()).replaceFirst("\\{", "{\"extra\":1,");
        assertThatThrownBy(() -> json.parse(bytes(top))).isInstanceOf(JacksonException.class);
        Map<String, Object> drops = WebAnalyticsFixtures.zeroEdgeDrops();
        drops.put("other", 0);
        assertThatThrownBy(() -> json.parse(bytes(WebAnalyticsFixtures.batch(List.of(), drops))))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void missingKeysFail() {
        Map<String, Object> event = new LinkedHashMap<>(WebAnalyticsFixtures.event("enquiry", Instant.now()));
        event.remove("goal");
        assertThatThrownBy(() -> json.parse(bytes(WebAnalyticsFixtures.batch(List.of(event)))))
                .isInstanceOf(JacksonException.class);
        Map<String, Object> drops = WebAnalyticsFixtures.zeroEdgeDrops();
        drops.remove("expired");
        assertThatThrownBy(() -> json.parse(bytes(WebAnalyticsFixtures.batch(List.of(), drops))))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.parse(bytes("{\"events\": []}"))).isInstanceOf(JacksonException.class);
    }

    @Test
    void explicitNullsParse() {
        IngestRequest request = json.parse(bytes("{\"events\": null, \"edgeDrops\": null}"));
        assertThat(request.events()).isNull();
        assertThat(request.edgeDrops()).isNull();
    }

    @Test
    void duplicateKeyFails() {
        String body = WebAnalyticsFixtures.batch(List.of()).replaceFirst("\\{", "{\"events\":[],");
        assertThatThrownBy(() -> json.parse(bytes(body))).isInstanceOf(JacksonException.class);
    }

    @Test
    void trailingTokensAndMalformedJsonFail() {
        assertThatThrownBy(() -> json.parse(bytes(WebAnalyticsFixtures.batch(List.of()) + " {}")))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.parse(bytes("{\"events\": [")))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.parse(bytes("[]"))).isInstanceOf(JacksonException.class);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
