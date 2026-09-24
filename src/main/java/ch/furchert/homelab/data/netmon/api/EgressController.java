package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.api.EgressDtos.EgressTop;
import ch.furchert.homelab.data.web.NetmonApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * {@code GET /api/netmon/egress/top?from&to&scope&namespace&workload&limit} (docs/060 §7.2, NM-2). Default window
 * 24 h; {@code scope} is {@code external} (default) or {@code all}; {@code limit} is a top-N limit (default 10, max
 * 50). Access control and the no-store headers come from the {@code /api/netmon/**} security chain.
 */
@RestController
@RequestMapping("/api/netmon/egress")
public class EgressController {

    private final EgressQueryRepository queries;
    private final Clock clock;

    public EgressController(EgressQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/top")
    public EgressTop top(@RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         @RequestParam(required = false) String scope,
                         @RequestParam(required = false) String namespace,
                         @RequestParam(required = false) String workload,
                         @RequestParam(required = false) String limit) {
        TimeWindow window = ApiParams.window(from, to, ApiParams.DEFAULT_WINDOW, clock);
        String scopeFilter = switch (ApiParams.optional(scope) == null ? "external" : scope.strip()) {
            case "external" -> "external";
            case "all" -> null;
            default -> throw NetmonApiException.invalidParameter("scope must be external or all");
        };
        int top = ApiParams.limit(limit, ApiParams.TOP_DEFAULT, ApiParams.TOP_MAX);
        return new EgressTop(queries.top(window, scopeFilter, ApiParams.optional(namespace),
                ApiParams.optional(workload), top));
    }
}
