package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.InboundDtos.FirewallEventItem;
import ch.furchert.homelab.data.netmon.api.InboundDtos.IpDetail;
import ch.furchert.homelab.data.netmon.api.InboundDtos.IpInbound;
import ch.furchert.homelab.data.netmon.api.InboundQueryRepository.Enrichment;
import ch.furchert.homelab.data.netmon.api.InboundQueryRepository.FirewallRow;
import ch.furchert.homelab.data.netmon.api.InboundQueryRepository.GroupFilter;
import ch.furchert.homelab.data.web.NetmonApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * {@code GET /api/netmon/ips/{ip}} (docs/060 §7.2): enrichment, blocklist hits, AbuseIPDB and recent
 * activity of one IP. Default window 7 d; a malformed IP is 400, an IP never seen is 404.
 * {@code logins} and {@code lan} stay {@code null} until NM-4 and NM-3.
 */
@RestController
@RequestMapping("/api/netmon/ips")
public class IpController {

    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    static final int RECENT_FIREWALL_EVENTS = 20;

    private final InboundQueryRepository queries;
    private final Clock clock;

    public IpController(InboundQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/{ip}")
    public IpDetail ip(@PathVariable("ip") String rawIp,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to) {
        String ip = ApiParams.ip(rawIp);
        TimeWindow window = ApiParams.window(from, to, DEFAULT_WINDOW, clock);
        Enrichment enrichment = queries.enrichment(ip)
                .orElseThrow(() -> NetmonApiException.notFound("No data for this IP"));
        GroupFilter filter = new GroupFilter(window, null, ip);
        IpInbound inbound = new IpInbound(
                queries.totals(filter).requests(),
                queries.topHosts(filter, ApiParams.TOP_DEFAULT),
                queries.topPaths(filter, ApiParams.TOP_DEFAULT),
                queries.statuses(filter));
        List<FirewallEventItem> firewall = queries.firewallEvents(window, null, null, ip, null, RECENT_FIREWALL_EVENTS)
                .stream().map(FirewallRow::item).toList();
        return new IpDetail(enrichment.ip(), enrichment.firstSeen(), enrichment.lastSeen(), enrichment.seenIn(),
                enrichment.country(), enrichment.asn(), enrichment.asnOrg(), queries.blocklistHits(ip),
                enrichment.abuseIpDb(), inbound, firewall, null, null);
    }
}
