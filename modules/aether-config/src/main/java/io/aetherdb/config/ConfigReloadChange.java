package io.aetherdb.config;

import java.util.Objects;

/** One effective setting change considered during a hot-reload attempt. */
public record ConfigReloadChange(
        String name, String previousValue, String nextValue, boolean applied, String reason) {
    public ConfigReloadChange {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        previousValue = Objects.requireNonNull(previousValue, "previousValue");
        nextValue = Objects.requireNonNull(nextValue, "nextValue");
        reason = Objects.requireNonNull(reason, "reason");
        if (!applied && reason.isBlank()) throw new IllegalArgumentException("denied changes require a reason");
    }
}
