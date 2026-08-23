package io.aetherdb.observability.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small thread-safe metric registry for tests, embedded mode, and exporter adapters. */
public final class InMemoryMetricRegistry {
    private final Clock clock;
    private final Map<String, MetricDescriptor> descriptors = new LinkedHashMap<>();
    private final List<MetricObservation> observations = new ArrayList<>();

    public InMemoryMetricRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void register(MetricDescriptor descriptor) {
        MetricDescriptor previous = descriptors.putIfAbsent(descriptor.name(), descriptor);
        if (previous != null && !previous.equals(descriptor))
            throw new IllegalArgumentException("conflicting metric descriptor: " + descriptor.name());
    }

    public synchronized void observe(MetricDescriptor descriptor, MetricLabels labels, double value) {
        register(descriptor);
        observations.add(new MetricObservation(descriptor, labels, value, clock.instant()));
    }

    public synchronized List<MetricObservation> snapshot() {
        return List.copyOf(observations);
    }

    public synchronized Map<String, MetricDescriptor> descriptors() {
        return Map.copyOf(descriptors);
    }
}
