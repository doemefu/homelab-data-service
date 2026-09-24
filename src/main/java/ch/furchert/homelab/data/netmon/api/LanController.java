package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.LanDtos.LanConnections;
import ch.furchert.homelab.data.netmon.api.LanDtos.SshAuth;
import ch.furchert.homelab.data.netmon.api.LanDtos.UfwBlocks;
import ch.furchert.homelab.data.netmon.api.LanDtos.UfwTotals;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * {@code GET /api/netmon/lan/connections}, {@code /lan/ufw-blocks} and {@code /lan/ssh-auth} (docs/060 §7.2,
 * NM-3). Default window 24 h. Access control and the no-store headers come from the {@code /api/netmon/**}
 * security chain.
 */
@RestController
@RequestMapping("/api/netmon/lan")
public class LanController {

    private final LanQueryRepository queries;
    private final Clock clock;

    public LanController(LanQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/connections")
    public LanConnections connections(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String node,
                                      @RequestParam(required = false) String dport) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        return new LanConnections(queries.connections(window, ApiParams.optional(node), ApiParams.optionalPort(dport)));
    }

    @GetMapping("/ufw-blocks")
    public UfwBlocks ufwBlocks(@RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) String node,
                               @RequestParam(required = false) String limit) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        int top = ApiParams.limit(limit, ApiParams.TOP_DEFAULT, ApiParams.TOP_MAX);
        String nodeFilter = ApiParams.optional(node);
        return new UfwBlocks(true, new UfwTotals(queries.ufwTotal(window, nodeFilter)),
                queries.topUfwBlocks(window, nodeFilter, top));
    }

    @GetMapping("/ssh-auth")
    public SshAuth sshAuth(@RequestParam(required = false) String from,
                           @RequestParam(required = false) String to,
                           @RequestParam(required = false) String node) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        return new SshAuth(queries.sshAuth(window, ApiParams.optional(node)));
    }
}
