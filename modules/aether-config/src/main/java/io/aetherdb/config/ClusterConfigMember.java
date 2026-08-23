package io.aetherdb.config;

import java.util.Objects;

/** Configuration snapshot advertised by one voting member during cluster compatibility checks. */
public record ClusterConfigMember(
        String nodeId, AetherConfiguration configuration, int supportedCompatibilityEpoch) {
    public ClusterConfigMember {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        Objects.requireNonNull(configuration, "configuration");
        if (supportedCompatibilityEpoch < 1)
            throw new IllegalArgumentException("supportedCompatibilityEpoch must be positive");
    }
}
