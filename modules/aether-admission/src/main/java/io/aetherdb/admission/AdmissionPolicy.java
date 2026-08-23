package io.aetherdb.admission;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable admission policy with per-resource limits and bounded slowdown delay. */
public record AdmissionPolicy(
        Map<AdmissionResource, ResourceLimit> limits, Duration maximumSlowdownDelay) {
    public AdmissionPolicy {
        Objects.requireNonNull(limits, "limits");
        EnumMap<AdmissionResource, ResourceLimit> copied = new EnumMap<>(AdmissionResource.class);
        for (Map.Entry<AdmissionResource, ResourceLimit> entry : limits.entrySet()) {
            if (entry.getKey() != entry.getValue().resource())
                throw new IllegalArgumentException("limit key does not match resource");
            copied.put(entry.getKey(), entry.getValue());
        }
        limits = Map.copyOf(copied);
        maximumSlowdownDelay = Objects.requireNonNull(maximumSlowdownDelay, "maximumSlowdownDelay");
        if (maximumSlowdownDelay.isNegative())
            throw new IllegalArgumentException("maximum slowdown must be non-negative");
    }

    public static AdmissionPolicy of(ResourceLimit limit, ResourceLimit... more) {
        EnumMap<AdmissionResource, ResourceLimit> limits = new EnumMap<>(AdmissionResource.class);
        limits.put(limit.resource(), limit);
        for (ResourceLimit item : more) limits.put(item.resource(), item);
        return new AdmissionPolicy(limits, Duration.ofMillis(100));
    }

    public static AdmissionPolicy empty() {
        return new AdmissionPolicy(Map.of(), Duration.ZERO);
    }

    public List<ResourceLimit> orderedLimits() {
        return limits.values().stream()
                .sorted(java.util.Comparator.comparing(limit -> limit.resource().name()))
                .toList();
    }
}
