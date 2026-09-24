package ch.furchert.homelab.data.integration;

import ch.furchert.homelab.data.AbstractIntegrationTest;
import ch.furchert.homelab.data.support.NetmonTables;
import ch.furchert.homelab.data.support.TestJwks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /egress/top (docs/060 §7.1, §7.2), checked against the shape furchert-ch consumes
 * ({@code EgressFlow} in furchert-ch {@code src/lib/netmon/client.ts}): 14 fields, nullable
 * namespace/workload/container/node/fqdn/firstSeenInWindow, string destinationIp, boolean isNew.
 */
class EgressApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URI = "/api/netmon/egress/top";
    private static final String FROM = "2026-09-24T08:00:00Z";
    private static final String TO = "2026-09-24T10:00:00Z";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcClient jdbc;

    private final String bearer = "Bearer " + TestJwks.furchertChClientToken();

    @BeforeEach
    void seed() {
        NetmonTables.clear(jdbc);
        // litellm → 34.117.59.81:443 across a rollout (two pods) and two windows; new in the first one.
        row("08", "mba1", "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm", "apps", "litellm-5d8f7c9b6-x2k9p", "litellm",
                "litellm", "34.117.59.81:443", "34.117.59.81", "external", "old.example.com", 100, 1000, 1, 0, true);
        row("09", "raspi5", "/k8s/apps/litellm-7c4b8d9f5b-q8w2z/litellm", "apps", "litellm-7c4b8d9f5b-q8w2z", "litellm",
                "litellm", "34.117.59.81:443", "34.117.59.81", "external", "new.example.com", 200, 2000, 2, 1, false);
        row("09", "mba1", "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm", "apps", "litellm-5d8f7c9b6-x2k9p", "litellm",
                "litellm", "34.117.59.81:443", "34.117.59.81", "external", null, 1, 1, 1, 0, false);
        // A destination coroot reports only by name.
        row("09", "mba1", "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm", "apps", "litellm-5d8f7c9b6-x2k9p", "litellm",
                "litellm", "api.anthropic.com:443", null, "external", "api.anthropic.com", 500, 900, 3, 0, false);
        // A host process.
        row("09", "raspi5", "/system.slice/k3s.service", null, null, "k3s.service", null, "140.82.121.4:443",
                "140.82.121.4", "external", "github.com", 10, 20, 1, 0, true);
        // In-cluster traffic: only with scope=all.
        row("09", "mba1", "/k8s/apps/furchert-ch-7fb9c6d48-m4n5q/furchert-ch", "apps", "furchert-ch-7fb9c6d48-m4n5q",
                "furchert-ch", "furchert-ch", "10.43.0.10:8080", "10.42.1.7", "pod", null, 99999, 99999, 9, 2, false);
        // Another namespace.
        row("09", "mba1", "/k8s/flux-system/source-controller-6b8d9f7c5-x7kqp/manager", "flux-system",
                "source-controller-6b8d9f7c5-x7kqp", "manager", "source-controller", "140.82.121.3:443", "140.82.121.3",
                "external", null, 1, 1, 1, 0, true);
        // Outside the window.
        row("06", "mba1", "/k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm", "apps", "litellm-5d8f7c9b6-x2k9p", "litellm",
                "litellm", "34.117.59.81:443", "34.117.59.81", "external", null, 1_000_000, 1_000_000, 1, 0, true);
    }

    private void row(String hour, String node, String containerId, String namespace, String pod, String container,
                     String workload, String destination, String ip, String scope, String fqdn, long sent, long received,
                     long connects, long failed, boolean isNew) {
        String key = namespace == null ? containerId : namespace + "/" + workload + "/" + container;
        String actual = ip == null ? "" : (destination.startsWith("10.43.") ? ip + ":8080" : destination);
        jdbc.sql("""
                        INSERT INTO netmon.egress_flow_snapshots (window_start, window_end, node, container_id, namespace, pod,
                            container, workload, workload_key, destination, actual_destination, destination_ip, destination_port,
                            destination_scope, fqdn, bytes_sent, bytes_received, connects, failed_connects, is_new, source)
                        VALUES (CAST(:ws AS timestamptz), CAST(:ws AS timestamptz) + interval '1 hour', :node, :cid, :ns, :pod,
                                :container, :workload, :key, :dst, :actual, CAST(:ip AS inet),
                                CAST(split_part(:dst, ':', 2) AS integer), :scope, :fqdn, :sent, :received, :connects, :failed,
                                :isNew, 'prometheus-coroot')
                        """)
                .param("ws", "2026-09-24T" + hour + ":00:00Z").param("node", node).param("cid", containerId)
                .param("ns", namespace).param("pod", pod).param("container", container).param("workload", workload)
                .param("key", key).param("dst", destination).param("actual", actual).param("ip", ip).param("scope", scope)
                .param("fqdn", fqdn).param("sent", sent).param("received", received).param("connects", connects)
                .param("failed", failed).param("isNew", isNew)
                .update();
    }

    private ResultActions call(String... params) throws Exception {
        var request = get(URI).header("Authorization", bearer);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    @Test
    void externalFlowsAggregatedAcrossRolloutsAndWindows() throws Exception {
        call("from", FROM, "to", TO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[*].destinationIp",
                        contains("34.117.59.81", "api.anthropic.com", "140.82.121.4", "140.82.121.3")))
                .andExpect(jsonPath("$.items[0]", aMapWithSize(14)))
                .andExpect(jsonPath("$.items[0]", hasKey("isNew")))
                .andExpect(jsonPath("$.items[0].namespace").value("apps"))
                .andExpect(jsonPath("$.items[0].workload").value("litellm"))
                .andExpect(jsonPath("$.items[0].container").value("litellm"))
                .andExpect(jsonPath("$.items[0].node").value("mba1"))
                .andExpect(jsonPath("$.items[0].destinationPort").value(443))
                .andExpect(jsonPath("$.items[0].fqdn").value("new.example.com"))
                .andExpect(jsonPath("$.items[0].scope").value("external"))
                .andExpect(jsonPath("$.items[0].bytesSent").value(301))
                .andExpect(jsonPath("$.items[0].bytesReceived").value(3001))
                .andExpect(jsonPath("$.items[0].connects").value(4))
                .andExpect(jsonPath("$.items[0].failedConnects").value(1))
                .andExpect(jsonPath("$.items[0].firstSeenInWindow").value("2026-09-24T08:00:00Z"))
                .andExpect(jsonPath("$.items[0].isNew").value(true))
                .andExpect(jsonPath("$.items[1].fqdn").value("api.anthropic.com"))
                .andExpect(jsonPath("$.items[1].isNew").value(false))
                .andExpect(jsonPath("$.items[2].namespace").value(nullValue()))
                .andExpect(jsonPath("$.items[2].workload").value(nullValue()))
                .andExpect(jsonPath("$.items[2].container").value("k3s.service"))
                .andExpect(jsonPath("$.items[3].fqdn").value(nullValue()))
                .andExpect(jsonPath("$.items[3].namespace").value("flux-system"));
    }

    @Test
    void scopeAllIncludesInClusterTraffic() throws Exception {
        call("from", FROM, "to", TO, "scope", "all", "limit", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].destinationIp").value("10.42.1.7"))
                .andExpect(jsonPath("$.items[0].scope").value("pod"));
    }

    @Test
    void namespaceAndWorkloadFilters() throws Exception {
        call("from", FROM, "to", TO, "namespace", "flux-system")
                .andExpect(jsonPath("$.items[*].workload", contains("source-controller")));
        call("from", FROM, "to", TO, "workload", "litellm")
                .andExpect(jsonPath("$.items[*].destinationIp", contains("34.117.59.81", "api.anthropic.com")));
    }

    @Test
    void fromDefaultsToOneDayBeforeTo() throws Exception {
        call("to", "2026-09-24T07:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].bytesSent").value(1_000_000));
    }

    @Test
    void invalidScopeOrLimitIs400() throws Exception {
        call("scope", "internal")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_parameter"))
                .andExpect(jsonPath("$.instance").value(URI));
        call("limit", "51").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_parameter"));
        call("limit", "0").andExpect(status().isBadRequest());
        call("from", TO, "to", FROM).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_window"));
    }

    @Test
    void withoutTokenIs401AndWithAForeignSubjectIs403() throws Exception {
        mockMvc.perform(get(URI))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mockMvc.perform(get(URI).header("Authorization", "Bearer " + TestJwks.token(c -> c.subject("grafana"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
