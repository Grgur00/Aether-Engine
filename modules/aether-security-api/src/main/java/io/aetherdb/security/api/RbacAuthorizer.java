package io.aetherdb.security.api;

import java.util.Objects;

/** Deny-by-default RBAC authorizer. */
public final class RbacAuthorizer implements AetherAuthorizer {
    private final RbacPolicy policy;

    public RbacAuthorizer(RbacPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public AuthorizationDecision authorize(
            SecurityPrincipal principal, AetherPermission permission, ResourceId resource) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
        for (RoleDefinition role : policy.rolesFor(principal))
            for (RoleGrant grant : role.grants())
                if (grant.permits(permission, resource))
                    return AuthorizationDecision.allow("RBAC_ROLE_GRANT");
        return AuthorizationDecision.deny("RBAC_DENY_DEFAULT");
    }
}
