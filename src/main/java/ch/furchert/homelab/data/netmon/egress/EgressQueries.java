package ch.furchert.homelab.data.netmon.egress;

import java.util.List;

/**
 * The PromQL of docs/060 §4.6 for the {@code egress} collector, each evaluated at {@code time = T} for the window
 * {@code [T-1h, T)}. Only metrics on the §6.2 keep-list are used. {@link #PRESENCE} exists because a counter series
 * that first appears inside the window has a single sample, for which {@code increase()} returns nothing: without
 * it a brand-new single-connect destination would be missing from its first hour.
 */
final class EgressQueries {

    static final String BYTES_SENT = "topk(500, sum by (node, container_id, destination, actual_destination) "
            + "(increase(container_net_tcp_bytes_sent_total[1h])))";
    static final String BYTES_RECEIVED = "sum by (node, container_id, destination, actual_destination) "
            + "(increase(container_net_tcp_bytes_received_total[1h]))";
    static final String CONNECTS = "sum by (node, container_id, destination, actual_destination) "
            + "(increase(container_net_tcp_successful_connects_total[1h]))";
    /** The failed-connects counter has no {@code actual_destination} label, so that group is always empty. */
    static final String FAILED_CONNECTS = "sum by (node, container_id, destination, actual_destination) "
            + "(increase(container_net_tcp_failed_connects_total[1h]))";
    static final String FQDN = "max by (ip, fqdn) (last_over_time(ip_to_fqdn[1h]))";
    static final String PRESENCE = "group by (node, container_id, destination, actual_destination) "
            + "(last_over_time(container_net_tcp_successful_connects_total[1h]))";

    /** Nodes whose agent published any connect series in the hour before the evaluation time. */
    static final String AGENTS = "count by (node) (last_over_time(container_net_tcp_successful_connects_total[1h]))";

    /** Every query of one window, in the order the collector sends them. */
    static final List<String> WINDOW = List.of(BYTES_SENT, BYTES_RECEIVED, CONNECTS, FAILED_CONNECTS, FQDN, PRESENCE);

    private EgressQueries() {
    }
}
