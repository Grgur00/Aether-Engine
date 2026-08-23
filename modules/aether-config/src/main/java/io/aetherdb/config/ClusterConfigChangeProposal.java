package io.aetherdb.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Operator-approved staged cluster-wide configuration change proposal. */
public record ClusterConfigChangeProposal(String proposalId, List<ClusterConfigSettingChange> changes) {
    public ClusterConfigChangeProposal {
        if (proposalId == null || proposalId.isBlank())
            throw new IllegalArgumentException("proposalId is required");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (changes.isEmpty()) throw new IllegalArgumentException("proposal requires changes");
        Set<String> names = new LinkedHashSet<>();
        for (ClusterConfigSettingChange change : changes)
            if (!names.add(change.settingName()))
                throw new IllegalArgumentException("duplicate staged setting: " + change.settingName());
    }

    public Set<String> settingNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ClusterConfigSettingChange change : changes) names.add(change.settingName());
        return Set.copyOf(names);
    }
}
