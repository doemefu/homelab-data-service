package ch.furchert.homelab.data.netmon.prometheus;

import java.util.Map;

/**
 * One element of an instant-vector result: the series labels (without {@code __name__} when the query
 * aggregates) and the sample value, which can be {@code NaN} or infinite.
 */
public record PrometheusSample(Map<String, String> labels, double value) {

    public PrometheusSample {
        labels = Map.copyOf(labels);
    }

    public String label(String name) {
        return labels.get(name);
    }
}
