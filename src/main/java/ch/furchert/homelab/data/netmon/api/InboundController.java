package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.InboundDtos.FirewallEventItem;
import ch.furchert.homelab.data.netmon.api.InboundDtos.FirewallEventPage;
import ch.furchert.homelab.data.netmon.api.InboundDtos.InboundSummary;
import ch.furchert.homelab.data.netmon.api.InboundQueryRepository.FirewallRow;
import ch.furchert.homelab.data.netmon.api.InboundQueryRepository.GroupFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * {@code GET /api/netmon/inbound/summary} and {@code /inbound/firewall-events} (docs/060 §7.2). Access
 * control and the no-store headers come from the {@code /api/netmon/**} security chain.
 */
@RestController
@RequestMapping("/api/netmon/inbound")
public class InboundController {

    /** The timeline bucket is 1 h up to a 7-day window, otherwise 1 d. */
    static final Duration HOURLY_TIMELINE_MAX = Duration.ofDays(7);

    private final InboundQueryRepository queries;
    private final Clock clock;

    public InboundController(InboundQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public InboundSummary summary(@RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String host,
                                  @RequestParam(required = false) String limit) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        int top = ApiParams.limit(limit, ApiParams.TOP_DEFAULT, ApiParams.TOP_MAX);
        GroupFilter filter = new GroupFilter(window, ApiParams.optional(host), null);
        String unit = Duration.between(window.from(), window.to()).compareTo(HOURLY_TIMELINE_MAX) <= 0 ? "hour" : "day";
        return new InboundSummary(
                window,
                queries.totals(filter),
                queries.topClientIps(filter, top),
                queries.topCountries(filter, top),
                queries.topAsns(filter, top),
                queries.topHosts(filter, top),
                queries.topPaths(filter, top),
                queries.statuses(filter),
                queries.timeline(filter, unit));
    }

    @GetMapping("/firewall-events")
    public FirewallEventPage firewallEvents(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String action,
                                            @RequestParam(required = false) String host,
                                            @RequestParam(required = false) String ip,
                                            @RequestParam(required = false) String limit,
                                            @RequestParam(required = false) String cursor) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        int pageSize = ApiParams.limit(limit, ApiParams.LIST_DEFAULT, ApiParams.LIST_MAX);
        List<FirewallRow> rows = queries.firewallEvents(window, ApiParams.optional(action), ApiParams.optional(host),
                ApiParams.optionalIp(ip), ApiParams.Cursor.decode(cursor), pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<FirewallRow> page = more ? rows.subList(0, pageSize) : rows;
        List<FirewallEventItem> items = page.stream().map(FirewallRow::item).toList();
        String next = more ? page.getLast().position().encode() : null;
        return new FirewallEventPage(items, next);
    }
}
