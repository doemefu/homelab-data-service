package ch.furchert.homelab.data.webanalytics.api;

import ch.furchert.homelab.data.config.WebAnalyticsProperties;
import ch.furchert.homelab.data.netmon.collector.CollectorStatusService;
import ch.furchert.homelab.data.netmon.collector.NetmonCollector;
import ch.furchert.homelab.data.webanalytics.ingest.IngestActivity;
import ch.furchert.homelab.data.webanalytics.ingest.IngestCaps;
import ch.furchert.homelab.data.webanalytics.rollup.RollupPlanner;
import ch.furchert.homelab.data.webanalytics.rollup.ZurichDays;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code GET /api/webanalytics/status} (docs/100 §8.2): the {@code webanalytics} collectors (26 h stale rule),
 * {@code lastCompleteDay} (newest final day), {@code provisionalDay}, {@code rawRetentionStart} (WA-5), and ingest
 * activity from memory incl. the WA-5 {@code dailyCap} and {@code capped}. Filters are
 * ignored and echoed as such. Access control and no-store headers come from the {@code /api/webanalytics/**} chain.
 */
@RestController
public class WebAnalyticsStatusController {

    private final CollectorStatusService statuses;
    private final AggregateQueryRepository queries;
    private final IngestActivity activity;
    private final IngestCaps caps;
    private final Clock clock;
    private final ZoneId zone;
    private final RollupPlanner planner;

    WebAnalyticsStatusController(CollectorStatusService statuses, AggregateQueryRepository queries,
                                 IngestActivity activity, IngestCaps caps, Clock clock,
                                 WebAnalyticsProperties properties) {
        this.statuses = statuses;
        this.queries = queries;
        this.activity = activity;
        this.caps = caps;
        this.clock = clock;
        this.zone = properties.timezone();
        this.planner = new RollupPlanner(new ZurichDays(zone), properties.retention().eventsDays());
    }

    @GetMapping("/api/webanalytics/status")
    ReadDtos.StatusResponse status(@RequestParam(required = false) String locale,
                                   @RequestParam(required = false) String device) {
        ReadParams.Query query = ReadParams.parse(null, null, null, locale, device, false, null);
        LocalDate lastComplete = queries.lastCompleteDay().orElse(null);
        IngestActivity.Snapshot ingest = activity.snapshot();
        // rawRetentionStart = the freeze-guard (b) bound: the oldest Zurich day whose raw events are all retained.
        LocalDate rawRetentionStart = planner.oldestRetained(LocalDate.now(clock.withZone(zone)));
        return new ReadDtos.StatusResponse(statuses.statuses(NetmonCollector.WEBANALYTICS_GROUP), lastComplete,
                queries.provisionalDay(lastComplete).orElse(null), rawRetentionStart, RollupPlanner.DEFINITION_VERSION,
                new ReadDtos.Ingest(ingest.lastEventAt(), ingest.eventsToday(), (long) caps.dailyCap(), caps.capped()),
                query.filters());
    }
}
