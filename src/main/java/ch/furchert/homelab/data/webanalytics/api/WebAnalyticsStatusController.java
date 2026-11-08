package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.netmon.collector.CollectorStatusService;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.webanalytics.ingest.IngestActivity;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * {@code GET /api/webanalytics/status} (docs/100 §8.2): the {@code webanalytics} collectors (26 h stale rule),
 * {@code lastCompleteDay} (newest final day), {@code provisionalDay}, and ingest activity from memory. Filters are
 * ignored and echoed as such. Access control and no-store headers come from the {@code /api/webanalytics/**} chain.
 */
@RestController
public class WebAnalyticsStatusController {

    private final CollectorStatusService statuses;
    private final AggregateQueryRepository queries;
    private final IngestActivity activity;

    WebAnalyticsStatusController(CollectorStatusService statuses, AggregateQueryRepository queries,
                                 IngestActivity activity) {
        this.statuses = statuses;
        this.queries = queries;
        this.activity = activity;
    }

    @GetMapping("/api/webanalytics/status")
    ReadDtos.StatusResponse status(@RequestParam(required = false) String locale,
                                   @RequestParam(required = false) String device) {
        ReadParams.Query query = ReadParams.parse(null, null, null, locale, device, false, null);
        LocalDate lastComplete = queries.lastCompleteDay().orElse(null);
        IngestActivity.Snapshot ingest = activity.snapshot();
        return new ReadDtos.StatusResponse(statuses.statuses(NetmonCollector.WEBANALYTICS_GROUP), lastComplete,
                queries.provisionalDay(lastComplete).orElse(null), null, RollupPlanner.DEFINITION_VERSION,
                new ReadDtos.Ingest(ingest.lastEventAt(), ingest.eventsToday(), null, null), query.filters());
    }
}
