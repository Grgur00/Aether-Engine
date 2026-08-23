package io.aetherdb.observability.api;

import java.time.Instant;
import java.util.Objects;

/** One metric value observed at a point in time. */
public record MetricObservation(
        MetricDescriptor descriptor, MetricLabels labels, double value, Instant observedAt) {
    public MetricObservation {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("metric value is not finite");
        for (String label : labels.values().keySet())
            if (!descriptor.labels().contains(label))
                throw new IllegalArgumentException("label is not declared by descriptor: " + label);
    }
}
