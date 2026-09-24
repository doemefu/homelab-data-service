package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.netmon.egress.EgressLabels.Endpoint;
import ch.furchert.homelab.data.netmon.egress.EgressLabels.Identity;
import ch.furchert.homelab.data.netmon.ip.Cidr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/060 §3.3/§6.4 identity parsing and the coroot destination label shapes. */
class EgressLabelsTest {

    private static final Cidr POD = Cidr.of("10.42.0.0/16");
    private static final Cidr SERVICE = Cidr.of("10.43.0.0/16");
    private static final Cidr LAN = Cidr.of("192.168.1.0/24");

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", textBlock = """
            # container_id                                      | namespace    | pod                              | container          | workload             | workloadKey
            /k8s/apps/litellm-5d8f7c9b6-x2k9p/litellm           | apps         | litellm-5d8f7c9b6-x2k9p          | litellm            | litellm              | apps/litellm/litellm
            /k8s/apps/litellm-7c4b8d9f5b-q8w2z/litellm          | apps         | litellm-7c4b8d9f5b-q8w2z         | litellm            | litellm              | apps/litellm/litellm
            /k8s/monitoring/coroot-node-agent-x2k9p/agent       | monitoring   | coroot-node-agent-x2k9p          | agent              | coroot-node-agent    | monitoring/coroot-node-agent/agent
            /k8s/apps/postgresql-0/postgresql                   | apps         | postgresql-0                     | postgresql         | postgresql-0         | apps/postgresql-0/postgresql
            /k8s-cronjob/apps/n8n-backup/backup                 | apps         | null                             | backup             | n8n-backup           | apps/n8n-backup/backup
            /k8s/flux-system/source-controller-6b8d9f7c5-x7kqp/manager | flux-system | source-controller-6b8d9f7c5-x7kqp | manager | source-controller | flux-system/source-controller/manager
            /system.slice/k3s.service                           | null         | null                             | k3s.service        | null                 | /system.slice/k3s.service
            /system.slice/containerd.service                    | null         | null                             | containerd.service | null                 | /system.slice/containerd.service
            """, delimiter = '|')
    void identityTable(String containerId, String namespace, String pod, String container, String workload,
                       String workloadKey) {
        assertThat(EgressLabels.identity(containerId))
                .contains(new Identity(namespace, pod, container, workload, workloadKey));
    }

    @Test
    void rolloutKeepsTheSameIdentity() {
        String before = EgressLabels.identity("/k8s/apps/furchert-ch-5d8f7c9b6-x2k9p/furchert-ch").orElseThrow().workloadKey();
        String after = EgressLabels.identity("/k8s/apps/furchert-ch-7fb9c6d48-m4n5q/furchert-ch").orElseThrow().workloadKey();
        assertThat(before).isEqualTo(after).isEqualTo("apps/furchert-ch/furchert-ch");
    }

    @Test
    void sameOwnerNameInAnotherNamespaceIsAnotherIdentity() {
        assertThat(EgressLabels.identity("/k8s/apps/web-5d8f7c9b6-x2k9p/web").orElseThrow().workloadKey())
                .isNotEqualTo(EgressLabels.identity("/k8s/other/web-5d8f7c9b6-x2k9p/web").orElseThrow().workloadKey());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"k8s/apps/a/b", "/k8s/Apps/a-1/b", "/k8s/apps/a b/c", "/k8s/apps//c", "/system.slice/x\n"})
    void rejectsMalformedContainerIds(String containerId) {
        assertThat(EgressLabels.identity(containerId)).isEmpty();
    }

    @Test
    void overlongContainerIdIsRejected() {
        assertThat(EgressLabels.identity("/" + "a".repeat(600))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", textBlock = """
            34.117.59.81:443                  | 34.117.59.81  | null              | 443
            [2001:DB8:0:0:0:0:0:1]:443        | 2001:db8::1   | null              | 443
            [::ffff:203.0.113.9]:80           | 203.0.113.9   | null              | 80
            api.anthropic.com:443             | null          | api.anthropic.com | 443
            GitHub.COM.:22                    | null          | github.com        | 22
            """, delimiter = '|')
    void endpoints(String value, String ip, String name, int port) {
        assertThat(EgressLabels.endpoint(value)).contains(new Endpoint(ip, name, port));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1.2.3.4", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:-1", "1.2.3.4:4x3", "2001:db8::1:443",
            "[1.2.3.4]:443", "[2001:db8::1]443", "localhost:80", "bad name.example:443", "-x.example:443", ":443"})
    void rejectsMalformedEndpoints(String value) {
        assertThat(EgressLabels.endpoint(value)).isEmpty();
    }

    @Test
    void targetPrefersTheActualDestinationAndFallsBackToTheDestination() {
        assertThat(EgressLabels.target("10.43.0.10:80", "10.42.1.7:8080")).contains(new Endpoint("10.42.1.7", null, 8080));
        assertThat(EgressLabels.target("34.117.59.81:443", "")).contains(new Endpoint("34.117.59.81", null, 443));
        assertThat(EgressLabels.target("34.117.59.81:443", null)).contains(new Endpoint("34.117.59.81", null, 443));
        assertThat(EgressLabels.target("api.anthropic.com:443", "")).contains(new Endpoint(null, "api.anthropic.com", 443));
        // A post-NAT address is always an IP.
        assertThat(EgressLabels.target("10.43.0.10:80", "evil.example:80")).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(textBlock = """
            127.0.0.1:5432      | loopback
            [::1]:5432          | loopback
            10.42.3.7:8080      | pod
            10.43.0.10:80       | service
            192.168.1.20:1883   | lan
            192.168.2.20:1883   | external
            34.117.59.81:443    | external
            api.github.com:443  | external
            """, delimiter = '|')
    void scopes(String value, String scope) {
        assertThat(EgressLabels.scope(EgressLabels.endpoint(value).orElseThrow(), POD, SERVICE, LAN)).isEqualTo(scope);
    }

    @Test
    void fqdnNormalisation() {
        assertThat(EgressLabels.fqdn("API.Anthropic.com.")).contains("api.anthropic.com");
        assertThat(EgressLabels.fqdn("bad name")).isEmpty();
        assertThat(EgressLabels.fqdn("localhost")).isEmpty();
        assertThat(EgressLabels.fqdn("")).isEmpty();
        assertThat(EgressLabels.fqdn("a".repeat(300) + ".com")).isEmpty();
    }
}
