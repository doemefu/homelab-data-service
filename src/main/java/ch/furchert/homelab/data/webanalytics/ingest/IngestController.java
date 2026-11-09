package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.web.NetmonApiException;
import ch.furchert.homelab.data.web.ProblemCode;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.EventDto;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestRequest;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.CappedResponse;
import ch.furchert.homelab.data.webanalytics.ingest.IngestDtos.IngestResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * {@code POST /api/webanalytics/events} (docs/100 §6.2, §6.3). Access control and the no-store headers come from
 * the {@code /api/webanalytics/**} security chain, which runs before the body is read. The body is read here
 * through a byte cap (Content-Length first, then a counted stream that also covers chunked bodies) and parsed by
 * {@link IngestJson}. Problem details are fixed texts; nothing from the body is echoed or logged. A capped batch
 * (WA-5) gets 429 with the §6.3 body, not a problem document.
 */
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestJson json;
    private final IngestService service;
    private final CappedBatchRecorder cappedRecorder;

    public IngestController(IngestJson json, IngestService service, CappedBatchRecorder cappedRecorder) {
        this.json = json;
        this.service = service;
        this.cappedRecorder = cappedRecorder;
    }

    @PostMapping(path = "/api/webanalytics/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(HttpServletRequest request, JwtAuthenticationToken authentication)
            throws IOException {
        IngestRequest batch = parse(readBody(request));
        checkBatch(batch);
        IngestOutcome outcome;
        try {
            outcome = service.ingest(batch, authentication.getToken().getSubject());
        } catch (IngestCappedException e) {
            // The batch transaction rolled back: nothing stored or counted, edgeDrops included (docs/100 §6.3).
            cappedRecorder.record(e.capped());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                    .body(new CappedResponse(0, 0, 0, true, e.capped().scope().label(),
                            IngestCaps.format(e.capped().retryAt())));
        } catch (DataAccessException | TransactionException e) {
            // Class name only: database messages can quote the failing row (incl. the visitor id).
            log.warn("[webanalytics] batch write failed: {}", e.getClass().getSimpleName());
            throw new WebAnalyticsStorageException();
        }
        return ResponseEntity.ok(new IngestResponse(outcome.accepted(), outcome.duplicates(), outcome.rejectedTotal(),
                false));
    }

    /**
     * 500 {@code internal} for a failed batch write. The failure was already logged with its class name only, so
     * this handler logs nothing (the generic advice would add an ERROR line with a stack trace).
     */
    @ExceptionHandler(WebAnalyticsStorageException.class)
    ResponseEntity<ProblemDetail> storageFailure(HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        body.setType(URI.create("about:blank"));
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", ProblemCode.INTERNAL);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > IngestLimits.MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(IngestLimits.MAX_BODY_BYTES + 1);
        }
        if (body.length > IngestLimits.MAX_BODY_BYTES) {
            throw tooLarge();
        }
        return body;
    }

    private IngestRequest parse(byte[] body) {
        try {
            return json.parse(body);
        } catch (JacksonException e) {
            // Never log or return the message: Jackson quotes the offending input.
            throw invalidBody();
        }
    }

    /** Batch-level rules (docs/100 §6.2): envelope members present, ≤ 200 events (else 413), edgeDrops in range. */
    static void checkBatch(IngestRequest batch) {
        if (batch == null || batch.events() == null || batch.edgeDrops() == null) {
            throw invalidBody();
        }
        for (EventDto event : batch.events()) {
            if (event == null) {
                throw invalidBody();
            }
        }
        if (batch.events().size() > IngestLimits.MAX_EVENTS) {
            throw new NetmonApiException(HttpStatus.CONTENT_TOO_LARGE, ProblemCode.INVALID_PARAMETER,
                    "A batch carries at most " + IngestLimits.MAX_EVENTS + " events");
        }
        for (Integer count : batch.edgeDrops().byReason().values()) {
            if (count == null || count < 0 || count > IngestLimits.MAX_EDGE_DROP_COUNT) {
                throw invalidBody();
            }
        }
    }

    private static NetmonApiException tooLarge() {
        return new NetmonApiException(HttpStatus.CONTENT_TOO_LARGE, ProblemCode.INVALID_PARAMETER,
                "The request body exceeds " + IngestLimits.MAX_BODY_BYTES + " bytes");
    }

    private static NetmonApiException invalidBody() {
        return new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                "The request body is not a valid web analytics batch");
    }
}
