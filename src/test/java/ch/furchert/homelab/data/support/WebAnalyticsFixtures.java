package ch.furchert.homelab.data.support;

import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EventDto;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loader for the shared C13 fixtures ({@code src/test/resources/webanalytics/c13-fixtures.json}, docs/100 §6.2)
 * and builders for ingest bodies and write tokens. Every materialisation gets fresh ids, so tests never collide.
 */
public final class WebAnalyticsFixtures {

    public static final String EVENTS = "/api/webanalytics/events";
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Pattern NOW = Pattern.compile("^now([+-])([0-9]+)m$");
    private static final Map<String, Object> FILE = load();

    /** One fixture case; {@code expect} is {@code accepted} or {@code rejected:<reason>}. */
    public record Case(String name, Map<String, Object> event, String expect) {

        public boolean accepted() {
            return "accepted".equals(expect);
        }

        /** The expected reject label, or null for an accepted case. */
        public String reason() {
            return accepted() ? null : expect.substring("rejected:".length());
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private WebAnalyticsFixtures() {
    }

    @SuppressWarnings("unchecked")
    public static List<Case> cases(Instant now) {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) FILE.get("cases");
        return cases.stream()
                .map(c -> new Case((String) c.get("name"),
                        event((String) c.get("base"), now, (Map<String, Object>) c.get("set")),
                        (String) c.get("expect")))
                .toList();
    }

    public static Map<String, Object> event(String base, Instant now) {
        return event(base, now, Map.of());
    }

    /** A fresh event of the given base with the overrides applied (a null value is kept as an explicit null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> event(String base, Instant now, Map<String, Object> overrides) {
        Map<String, Object> bases = (Map<String, Object>) FILE.get("bases");
        Map<String, Object> event = new LinkedHashMap<>((Map<String, Object>) bases.get(base));
        event.putAll(overrides);
        event.replaceAll((key, value) -> resolve(value, now));
        return event;
    }

    public static Map<String, Object> zeroEdgeDrops() {
        Map<String, Object> drops = new LinkedHashMap<>();
        for (String key : List.of("bot", "signed_in", "consent", "invalid", "path", "rate_limited", "mint_limited",
                "overflow", "expired")) {
            drops.put(key, 0);
        }
        return drops;
    }

    public static String batch(List<Map<String, Object>> events) {
        return batch(events, zeroEdgeDrops());
    }

    public static String batch(List<Map<String, Object>> events, Map<String, Object> edgeDrops) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events);
        body.put("edgeDrops", edgeDrops);
        return JSON.writeValueAsString(body);
    }

    public static EventDto toDto(Map<String, Object> event) {
        return JSON.convertValue(event, EventDto.class);
    }

    /** A client-credentials token of furchert-ch with only {@code analytics:write} (docs/100 §11). */
    public static String writeToken() {
        return TestJwks.token(claims -> claims.claim("scope", List.of("analytics:write")));
    }

    private static Object resolve(Object value, Instant now) {
        if (!(value instanceof String text)) {
            return value;
        }
        if ("<uuid>".equals(text)) {
            return UUID.randomUUID().toString();
        }
        Matcher m = NOW.matcher(text);
        if (m.matches()) {
            long minutes = Long.parseLong(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
            return now.plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS).toString();
        }
        return text;
    }

    private static Map<String, Object> load() {
        try (InputStream in = WebAnalyticsFixtures.class.getResourceAsStream("/webanalytics/c13-fixtures.json")) {
            if (in == null) {
                throw new IllegalStateException("webanalytics/c13-fixtures.json missing from the test classpath");
            }
            return JSON.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
