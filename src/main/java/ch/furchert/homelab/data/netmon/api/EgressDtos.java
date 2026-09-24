package ch.furchert.homelab.data.netmon.api;

import java.time.Instant;
import java.util.List;

/** Response shape of {@code GET /egress/top} (NM-2), field for field as docs/060 §7.2 defines it. */
public final class EgressDtos {

    private EgressDtos() {
    }

    public record EgressTop(List<EgressTopItem> items) {
    }

    /**
     * One {@code (namespace, workload, container, destination, port)} aggregate over the window.
     *
     * @param namespace         null for host processes
     * @param workload          null for host processes
     * @param container         the container, or the systemd unit of a host process
     * @param node              the most frequent node of the aggregated rows
     * @param destinationIp     the post-NAT IP literal; for an external destination coroot reports only by name
     *                          (several IPs), that name — {@code fqdn} then holds the same name
     * @param fqdn              the {@code ip_to_fqdn} name of the newest row that has one, or null
     * @param scope             {@code external}, {@code lan}, {@code pod}, {@code service} or {@code loopback}
     * @param firstSeenInWindow start of the earliest hourly window inside the query window with this flow
     * @param isNew             this workload has not reached this destination in the 30 d before that window
     */
    public record EgressTopItem(String namespace, String workload, String container, String node, String destinationIp,
                                int destinationPort, String fqdn, String scope, long bytesSent, long bytesReceived,
                                long connects, long failedConnects, Instant firstSeenInWindow, boolean isNew) {
    }
}
