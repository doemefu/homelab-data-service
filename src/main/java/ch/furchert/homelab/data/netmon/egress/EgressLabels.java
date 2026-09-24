package ch.furchert.homelab.data.netmon.egress;

import ch.furchert.homelab.data.netmon.ip.Cidr;
import ch.furchert.homelab.data.netmon.ip.IpAddresses;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the coroot-node-agent labels of docs/060 §3.3/§4.6 (verified against the v1.35.10 source,
 * {@code containers/registry.go} and {@code common/net.go}). Every value is untrusted (FQDNs come from DNS
 * answers), so anything outside the expected shapes is rejected rather than stored.
 */
final class EgressLabels {

    /** The pod-name suffix of §6.4's {@code <W>}: optional ReplicaSet hash, then the 5-character pod suffix. */
    private static final Pattern POD_SUFFIX = Pattern.compile(
            "(.+?)(?:-[bcdfghjklmnpqrstvwxz2456789]{6,10})?-[bcdfghjklmnpqrstvwxz2456789]{5}");
    private static final Pattern K8S_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?");
    private static final Pattern CONTAINER_ID = Pattern.compile("/[\\x21-\\x7e]{1,511}");
    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9_]([a-z0-9_.-]{0,251}[a-z0-9_])?");
    private static final Cidr LOOPBACK_V4 = Cidr.of("127.0.0.0/8");
    private static final Cidr LOOPBACK_V6 = Cidr.of("::1/128");

    private EgressLabels() {
    }

    /**
     * Where a {@code container_id} runs. Pods: {@code /k8s/<ns>/<pod>/<container>}, or
     * {@code /k8s-cronjob/<ns>/<cronjob>/<container>} (coroot collapses CronJob pods; {@code pod} is then null).
     * Anything else (systemd units such as {@code /system.slice/k3s.service}) is a host process: namespace, pod
     * and workload are null and {@code container} is the last path segment, so host processes stay apart.
     *
     * @param workload    the pod name without its ReplicaSet/DaemonSet suffix (StatefulSet names stay whole), or
     *                    the CronJob name
     * @param workloadKey the rollout-stable identity of {@code is_new}: {@code <ns>/<workload>/<container>} for pods
     *                    (the §6.4 {@code <W>} format), the raw {@code container_id} otherwise
     */
    record Identity(String namespace, String pod, String container, String workload, String workloadKey) {
    }

    /**
     * A destination: an IP literal in RFC 5952 form, or a lower-case DNS name when coroot groups an external
     * destination by name ({@code ip} is then null).
     */
    record Endpoint(String ip, String name, int port) {

        String host() {
            return ip != null ? ip : name;
        }
    }

    static Optional<Identity> identity(String containerId) {
        if (containerId == null || !CONTAINER_ID.matcher(containerId).matches()) {
            return Optional.empty();
        }
        String[] parts = containerId.split("/", -1);
        if (parts.length == 5 && ("k8s".equals(parts[1]) || "k8s-cronjob".equals(parts[1]))) {
            String namespace = parts[2];
            String name = parts[3];
            String container = parts[4];
            if (!K8S_NAME.matcher(namespace).matches() || !K8S_NAME.matcher(name).matches()
                    || !K8S_NAME.matcher(container).matches()) {
                return Optional.empty();
            }
            boolean cronJob = "k8s-cronjob".equals(parts[1]);
            String workload = cronJob ? name : workload(name);
            return Optional.of(new Identity(namespace, cronJob ? null : name, container, workload,
                    namespace + "/" + workload + "/" + container));
        }
        String last = parts[parts.length - 1];
        return Optional.of(new Identity(null, null, last.isEmpty() ? null : last, null, containerId));
    }

    /** {@code litellm-5d8f7c9b6-x2k9p} → {@code litellm}; {@code postgresql-0} stays {@code postgresql-0}. */
    static String workload(String pod) {
        Matcher m = POD_SUFFIX.matcher(pod);
        return m.matches() ? m.group(1) : pod;
    }

    /**
     * Parses {@code ip:port}, {@code [ipv6]:port} or {@code name:port}; empty for anything else, including the
     * empty string coroot emits for an unknown {@code actual_destination}.
     */
    static Optional<Endpoint> endpoint(String value) {
        if (value == null || value.isEmpty() || value.length() > 260) {
            return Optional.empty();
        }
        String host;
        String port;
        if (value.startsWith("[")) {
            int close = value.indexOf("]:");
            if (close < 0) {
                return Optional.empty();
            }
            host = value.substring(1, close);
            port = value.substring(close + 2);
            if (!host.contains(":")) {
                return Optional.empty();
            }
        } else {
            int colon = value.lastIndexOf(':');
            if (colon <= 0 || value.indexOf(':') != colon) {
                return Optional.empty();
            }
            host = value.substring(0, colon);
            port = value.substring(colon + 1);
        }
        Integer number = port(port);
        if (number == null) {
            return Optional.empty();
        }
        Optional<String> ip = IpAddresses.compressed(host);
        if (ip.isPresent()) {
            return Optional.of(new Endpoint(ip.get(), null, number));
        }
        if (host.contains(":")) {
            return Optional.empty();
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (!HOST_NAME.matcher(name).matches() || !name.contains(".")) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint(null, name, number));
    }

    /**
     * The post-NAT destination of a series: {@code actual_destination} when present (it must be an IP), else
     * {@code destination} (an IP, or a name for a name-grouped external destination).
     */
    static Optional<Endpoint> target(String destination, String actualDestination) {
        if (actualDestination != null && !actualDestination.isEmpty()) {
            return endpoint(actualDestination).filter(e -> e.ip() != null);
        }
        return endpoint(destination);
    }

    /** {@code loopback}, {@code pod}, {@code service}, {@code lan} or {@code external} (names are external). */
    static String scope(Endpoint endpoint, Cidr pod, Cidr service, Cidr lan) {
        if (endpoint.ip() == null) {
            return "external";
        }
        InetAddress address = IpAddresses.parse(endpoint.ip()).orElseThrow();
        if (LOOPBACK_V4.contains(address) || LOOPBACK_V6.contains(address)) {
            return "loopback";
        }
        if (pod.contains(address)) {
            return "pod";
        }
        if (service.contains(address)) {
            return "service";
        }
        if (lan.contains(address)) {
            return "lan";
        }
        return "external";
    }

    /** An {@code ip_to_fqdn} name, lower case without the trailing dot; empty if it is not a DNS name. */
    static Optional<String> fqdn(String value) {
        if (value == null || value.isEmpty() || value.length() > 254) {
            return Optional.empty();
        }
        String name = value.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return HOST_NAME.matcher(name).matches() && name.contains(".") ? Optional.of(name) : Optional.empty();
    }

    private static Integer port(String raw) {
        if (raw.isEmpty() || raw.length() > 5 || !raw.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int port = Integer.parseInt(raw);
        return port >= 1 && port <= 65_535 ? port : null;
    }
}
