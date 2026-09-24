package ch.furchert.homelab.data.netmon.egress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The §4.6 PromQL text: presence query, keep-list metrics only, no range functions on the gauge. */
class EgressQueriesTest {

    /** docs/060 §6.2 keep-list; anything else is dropped by the ServiceMonitor and would return nothing. */
    private static final Set<String> KEEP_LIST = Set.of(
            "container_net_tcp_successful_connects_total", "container_net_tcp_failed_connects_total",
            "container_net_tcp_bytes_sent_total", "container_net_tcp_bytes_received_total",
            "container_net_tcp_active_connections", "ip_to_fqdn");
    private static final Pattern METRIC = Pattern.compile("\\b(container_(?!id\\b)[a-z_]+|ip_to_fqdn)\\b");

    private static List<String> all() {
        List<String> queries = new ArrayList<>(EgressQueries.WINDOW);
        queries.add(EgressQueries.AGENTS);
        return queries;
    }

    @Test
    void theSixContractQueriesAreSentPerWindow() {
        assertThat(EgressQueries.WINDOW).containsExactly(EgressQueries.BYTES_SENT, EgressQueries.BYTES_RECEIVED,
                EgressQueries.CONNECTS, EgressQueries.FAILED_CONNECTS, EgressQueries.FQDN, EgressQueries.PRESENCE);
    }

    @Test
    void presenceQueryIsTheSeriesPresenceKeyQuery() {
        assertThat(EgressQueries.PRESENCE).isEqualTo(
                "group by (node, container_id, destination, actual_destination) "
                        + "(last_over_time(container_net_tcp_successful_connects_total[1h]))");
    }

    @Test
    void onlyKeepListMetricsAreQueried() {
        for (String query : all()) {
            Matcher m = METRIC.matcher(query);
            assertThat(m.find()).as(query).isTrue();
            do {
                assertThat(KEEP_LIST).as(query).contains(m.group(1));
            } while (m.find());
        }
    }

    @Test
    void countersUseIncreaseOverTheHourAndGaugesNoRangeFunctions() {
        assertThat(EgressQueries.BYTES_SENT).contains("increase(container_net_tcp_bytes_sent_total[1h])").startsWith("topk(500, ");
        assertThat(EgressQueries.BYTES_RECEIVED).contains("increase(container_net_tcp_bytes_received_total[1h])");
        assertThat(EgressQueries.CONNECTS).contains("increase(container_net_tcp_successful_connects_total[1h])");
        assertThat(EgressQueries.FAILED_CONNECTS).contains("increase(container_net_tcp_failed_connects_total[1h])");
        for (String query : all()) {
            assertThat(query).doesNotContain("container_net_tcp_active_connections[");
            assertThat(query).doesNotContain("rate(");
        }
    }

    @Test
    void flowQueriesGroupByTheContractKey() {
        for (String query : List.of(EgressQueries.BYTES_SENT, EgressQueries.BYTES_RECEIVED, EgressQueries.CONNECTS,
                EgressQueries.FAILED_CONNECTS, EgressQueries.PRESENCE)) {
            assertThat(query).contains("by (node, container_id, destination, actual_destination)");
        }
        assertThat(EgressQueries.FQDN).isEqualTo("max by (ip, fqdn) (last_over_time(ip_to_fqdn[1h]))");
    }
}
