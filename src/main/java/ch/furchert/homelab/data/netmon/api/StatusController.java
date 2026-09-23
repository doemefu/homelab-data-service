package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.collector.CollectorStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collector freshness for the UI's honest-fallback banner (docs/060 §7.2). Authorization and the
 * no-store caching headers are applied by the {@code /api/netmon/**} security chain.
 */
@RestController
@RequestMapping("/api/netmon")
public class StatusController {

    private final CollectorStatusService statusService;

    public StatusController(CollectorStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(statusService.statuses());
    }
}
