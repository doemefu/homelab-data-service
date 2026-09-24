package ch.furchert.homelab.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code netmon.prometheus.*} (docs/060 §4.6, §9): the cluster Prometheus that holds the node-script and
 * coroot metrics as 14-day transport. Set via {@code PROMETHEUS_URL}; no credentials.
 *
 * @param url base URL without a trailing {@code /api/v1}
 */
@ConfigurationProperties("netmon.prometheus")
public record PrometheusProperties(
        @DefaultValue("http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090") String url) {

    public PrometheusProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("netmon.prometheus.url must not be blank");
        }
        url = url.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
    }

    public String queryUrl() {
        return url + "/api/v1/query";
    }
}
