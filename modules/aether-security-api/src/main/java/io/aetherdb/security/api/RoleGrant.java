package io.aetherdb.security.api;

import java.util.Objects;

/** One permission grant scoped to a resource pattern. */
public record RoleGrant(AetherPermission permission, ResourceId resourceScope) {
    public RoleGrant {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resourceScope, "resourceScope");
    }

    boolean permits(AetherPermission requestedPermission, ResourceId requestedResource) {
        return permission.equals(requestedPermission) && resourceScope.covers(requestedResource);
    }
}
