package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * The strict parser of the ingest body (docs/100 §6.2, C13), separate from Boot's global mapper so the netmon
 * API is unaffected. Nesting ≤ 8, strings ≤ 512, document ≤ 64 KiB; unknown, duplicate or missing keys,
 * trailing tokens and every scalar coercion (number ↔ string ↔ boolean, float → int) fail. Callers must not
 * log or return the exception message: Jackson quotes the offending input.
 */
@Component
public class IngestJson {

    static final int MAX_NESTING_DEPTH = 8;
    static final int MAX_STRING_LENGTH = 512;

    private final JsonMapper mapper;

    public IngestJson() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxDocumentLength(IngestLimits.MAX_BODY_BYTES)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Boolean, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
                .build();
    }

    /** For tests: the configured mapper, to drive the raw parser past the binding layer. */
    JsonMapper mapper() {
        return mapper;
    }

    /** @throws tools.jackson.core.JacksonException for any schema or constraint violation */
    public IngestRequest parse(byte[] body) {
        return mapper.readValue(body, IngestRequest.class);
    }
}
