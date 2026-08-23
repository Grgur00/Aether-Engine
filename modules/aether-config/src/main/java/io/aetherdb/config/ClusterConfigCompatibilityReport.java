package io.aetherdb.config;

import java.util.List;
import java.util.Objects;

/** Compatibility result for the cluster-wide subset of voting-member configuration. */
public record ClusterConfigCompatibilityReport(
        boolean compatible, int votingMembers, List<String> stagedSettings, List<ClusterConfigCompatibilityIssue> issues) {
    public ClusterConfigCompatibilityReport {
        if (votingMembers <= 0) throw new IllegalArgumentException("votingMembers must be positive");
        stagedSettings = List.copyOf(Objects.requireNonNull(stagedSettings, "stagedSettings"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (compatible != issues.isEmpty())
            throw new IllegalArgumentException("compatible must match issue emptiness");
    }
}
