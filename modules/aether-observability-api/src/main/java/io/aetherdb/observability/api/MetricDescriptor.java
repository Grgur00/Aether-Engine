package io.aetherdb.observability.api;

import java.util.List;
import java.util.Objects;

/** Stable metric descriptor with validated name and low-cardinality label names. */
public record MetricDescriptor(String name, MetricKind kind, String unit, List<String> labels) {
    public MetricDescriptor {
        name = ObservabilityNames.requireValidMetricName(name);
        Objects.requireNonNull(kind, "kind");
        if (unit == null) unit = "";
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        labels.forEach(ObservabilityNames::requireValidLabelName);
    }
}
