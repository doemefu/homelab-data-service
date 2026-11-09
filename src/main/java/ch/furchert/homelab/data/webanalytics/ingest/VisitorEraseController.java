package ch.furchert.homelab.data.webanalytics.ingest;

import ch.furchert.homelab.data.web.NetmonApiException;
import ch.furchert.homelab.data.web.ProblemCode;
import ch.furchert.homelab.data.web.ProblemInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code DELETE /api/webanalytics/visitors/{visitorId}} (docs/100 §6.5): erases every {@code events} row of that id
 * and tombstones it for 2 h. 204 also when nothing matched (idempotent; called synchronously by the furchert-ch forget
 * handler), 400 for an id that is not a lowercase UUID v4. An incomplete erasure (bound, lock or statement timeout)
 * answers 503, any other storage failure 500, both {@code internal} and retryable. Access control comes from the
 * {@code /api/webanalytics/**} chain; problem bodies never contain the id ({@code instance} is the path template).
 */
@RestController
public class VisitorEraseController {

    private static final Pattern UUID_V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Logger log = LoggerFactory.getLogger(VisitorEraseController.class);

    private final VisitorEraseService service;

    public VisitorEraseController(VisitorEraseService service) {
        this.service = service;
    }

    @DeleteMapping("/api/webanalytics/visitors/{visitorId}")
    public ResponseEntity<Void> erase(@PathVariable String visitorId) {
        if (!UUID_V4.matcher(visitorId).matches()) {
            throw new NetmonApiException(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_PARAMETER,
                    "visitorId must be a lowercase UUID v4");
        }
        service.erase(UUID.fromString(visitorId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(VisitorEraseIncompleteException.class)
    ResponseEntity<ProblemDetail> unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Erasure not completed, retry");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> failed(RuntimeException e) {
        log.warn("[webanalytics] visitor erase failed: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setInstance(URI.create(ProblemInstance.VISITORS_TEMPLATE));
        body.setProperty("code", ProblemCode.INTERNAL);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
