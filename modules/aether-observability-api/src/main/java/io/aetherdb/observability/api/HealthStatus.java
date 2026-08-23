package io.aetherdb.observability.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Machine-readable health status with stable reason codes and bounded details. */
public record HealthStatus(
        HealthState state, String reasonCode, Instant observedAt, Map<String, String> details) {
    public HealthStatus {
        Objects.requireNonNull(state, "state");
        if (reasonCode == null || reasonCode.isBlank())
            throw new IllegalArgumentException("blank reason code");
        Objects.requireNonNull(observedAt, "observedAt");
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
        for (Map.Entry<String, String> entry : details.entrySet()) {
            ObservabilityNames.requireValidLabelName(entry.getKey());
            if (entry.getValue() == null || entry.getValue().length() > 256)
                throw new IllegalArgumentException("invalid health detail value");
        }
    }

    public static HealthStatus of(HealthState state, String reasonCode) {
        return new HealthStatus(state, reasonCode, Instant.now(), Map.of());
    }
}
