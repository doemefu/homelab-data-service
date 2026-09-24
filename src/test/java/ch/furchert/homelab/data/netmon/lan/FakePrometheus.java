package ch.furchert.homelab.data.netmon.lan;

import ch.furchert.homelab.data.config.PrometheusProperties;
import ch.furchert.homelab.data.netmon.prometheus.PrometheusClient;
import ch.furchert.homelab.data.netmon.prometheus.PrometheusSample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Prometheus answering fixed vectors per (query, evaluation time); anything else is an empty vector. */
class FakePrometheus extends PrometheusClient {

    private final Map<String, List<PrometheusSample>> answers = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    RuntimeException failure;

    FakePrometheus() {
        super(null, new PrometheusProperties("http://prometheus.test:9090"), null);
    }

    FakePrometheus on(String query, Instant time, PrometheusSample... samples) {
        answers.put(key(query, time), List.of(samples));
        return this;
    }

    @Override
    public List<PrometheusSample> query(String promql, Instant time) {
        calls.add(key(promql, time));
        if (failure != null) {
            throw failure;
        }
        return answers.getOrDefault(key(promql, time), List.of());
    }

    static PrometheusSample sample(double value, String... labels) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i += 2) {
            map.put(labels[i], labels[i + 1]);
        }
        return new PrometheusSample(map, value);
    }

    static String key(String query, Instant time) {
        return time.getEpochSecond() + " " + query;
    }
}
