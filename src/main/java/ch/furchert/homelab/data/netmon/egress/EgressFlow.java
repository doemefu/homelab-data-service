package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.netmon.egress.EgressLabels.Endpoint;
import ch.furchert.homelab.data.netmon.egress.EgressLabels.Identity;

/**
 * One validated {@code egress_flow_snapshots} row of a window (docs/060 §3.3), before {@code is_new} is computed.
 *
 * @param actualDestination the raw label, {@code ""} when coroot reports none
 * @param target            the post-NAT destination ({@code destination_ip}/{@code destination_port}, or the name)
 * @param fqdn              the {@code ip_to_fqdn} name, or the name of a name-grouped destination; may be null
 */
record EgressFlow(String node, String containerId, Identity identity, String destination, String actualDestination,
                  Endpoint target, String scope, String fqdn, long bytesSent, long bytesReceived, long connects,
                  long failedConnects) {
}
