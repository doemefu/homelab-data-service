package ch.furchert.homelab.data.netmon.api;

import ch.furchert.homelab.data.netmon.collector.CollectorStatus;

import java.util.List;

/** Body of {@code GET /api/netmon/status} (docs/060 §7.2). */
public record StatusResponse(List<CollectorStatus> collectors) {
}
