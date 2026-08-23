package io.aetherdb.config;

import java.util.List;
import java.util.Objects;

/** Result of evaluating a proposed admin/API configuration hot reload. */
public record ConfigReloadDecision(boolean accepted, List<ConfigReloadChange> changes, List<String> errors) {
    public ConfigReloadDecision {
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        boolean hasDeniedChange = changes.stream().anyMatch(change -> !change.applied());
        if (accepted != (!hasDeniedChange && errors.isEmpty()))
            throw new IllegalArgumentException("accepted must match denied changes and validation errors");
    }

    public List<ConfigReloadChange> appliedChanges() {
        return changes.stream().filter(ConfigReloadChange::applied).toList();
    }

    public List<ConfigReloadChange> deniedChanges() {
        return changes.stream().filter(change -> !change.applied()).toList();
    }
}
