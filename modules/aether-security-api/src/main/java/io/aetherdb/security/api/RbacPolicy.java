package io.aetherdb.security.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable deny-by-default RBAC policy. */
public record RbacPolicy(Map<String, RoleDefinition> roles, List<RoleBinding> bindings) {
    public RbacPolicy {
        roles = Map.copyOf(Objects.requireNonNull(roles, "roles"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        for (RoleBinding binding : bindings)
            if (!roles.containsKey(binding.roleName()))
                throw new IllegalArgumentException("binding references unknown role: " + binding.roleName());
    }

    public static RbacPolicy empty() {
        return new RbacPolicy(Map.of(), List.of());
    }

    Set<RoleDefinition> rolesFor(SecurityPrincipal principal) {
        return bindings.stream()
                .filter(binding -> binding.principalId().equals(principal.id()))
                .map(binding -> roles.get(binding.roleName()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
