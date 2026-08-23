package io.aetherdb.observability.api;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Low-cardinality metric label set with validated names. */
public record MetricLabels(Map<String, String> values) {
    public static final MetricLabels EMPTY = new MetricLabels(Map.of());

    public MetricLabels {
        Objects.requireNonNull(values, "values");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            ObservabilityNames.requireValidLabelName(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isBlank())
                throw new IllegalArgumentException("blank metric label value");
            if (entry.getValue().length() > 128)
                throw new IllegalArgumentException("metric label value is too long");
            sorted.put(entry.getKey(), entry.getValue());
        }
        values = Map.copyOf(sorted);
    }

    public static MetricLabels of(String key, String value) {
        return new MetricLabels(Map.of(key, value));
    }
}
