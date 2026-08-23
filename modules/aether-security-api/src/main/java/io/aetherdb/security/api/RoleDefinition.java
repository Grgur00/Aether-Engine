package io.aetherdb.security.api;

import java.util.Set;

/** Immutable role definition. */
public record RoleDefinition(String name, Set<RoleGrant> grants) {
    public RoleDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("role name is blank");
        grants = Set.copyOf(grants);
    }
}
