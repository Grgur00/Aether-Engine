package io.aetherdb.config;

import java.util.Objects;

/** One blocking cluster-wide configuration compatibility issue. */
public record ClusterConfigCompatibilityIssue(
        String code, String settingName, String nodeId, String detail) {
    public ClusterConfigCompatibilityIssue {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        settingName = Objects.requireNonNull(settingName, "settingName");
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        detail = Objects.requireNonNull(detail, "detail");
    }
}
