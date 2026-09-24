package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginEventItem;
import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginEventPage;
import ch.furchert.homelab.data.netmon.api.LoginDtos.LoginSummary;
import ch.furchert.homelab.data.netmon.api.LoginQueryRepository.EventRow;
import ch.furchert.homelab.data.web.NetmonApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/netmon/logins/summary} and {@code /logins/events} (docs/060 §7.2, NM-4). Access control and the
 * no-store headers come from the {@code /api/netmon/**} security chain.
 */
@RestController
@RequestMapping("/api/netmon/logins")
public class LoginController {

    static final Set<String> OUTCOMES = Set.of("success", "failure", "locked");

    private final LoginQueryRepository queries;
    private final Clock clock;

    public LoginController(LoginQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public LoginSummary summary(@RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(required = false) String limit) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        int top = ApiParams.limit(limit, ApiParams.TOP_DEFAULT, ApiParams.TOP_MAX);
        // Same bucket rule as the inbound timeline: 1 h up to a 7-day window, otherwise 1 d.
        String unit = Duration.between(window.from(), window.to())
                .compareTo(InboundController.HOURLY_TIMELINE_MAX) <= 0 ? "hour" : "day";
        return new LoginSummary(queries.totals(window), queries.byIp(window, top), queries.bySubject(window, top),
                queries.timeline(window, unit));
    }

    @GetMapping("/events")
    public LoginEventPage events(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) String outcome,
                                 @RequestParam(required = false) String ip,
                                 @RequestParam(required = false) String limit,
                                 @RequestParam(required = false) String cursor) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        int pageSize = ApiParams.limit(limit, ApiParams.LIST_DEFAULT, ApiParams.LIST_MAX);
        String outcomeFilter = ApiParams.optional(outcome);
        if (outcomeFilter != null && !OUTCOMES.contains(outcomeFilter)) {
            throw NetmonApiException.invalidParameter("outcome must be one of success, failure, locked");
        }
        List<EventRow> rows = queries.events(window, outcomeFilter, ApiParams.optionalIp(ip),
                ApiParams.Cursor.decode(cursor), pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<EventRow> page = more ? rows.subList(0, pageSize) : rows;
        List<LoginEventItem> items = page.stream().map(EventRow::item).toList();
        return new LoginEventPage(items, more ? page.getLast().position().encode() : null);
    }
}
