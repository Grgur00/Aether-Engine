package io.aetherdb.security.api;

/** Binds one principal ID to one named role. */
public record RoleBinding(String principalId, String roleName) {
    public RoleBinding {
        if (principalId == null || principalId.isBlank())
            throw new IllegalArgumentException("principal id is blank");
        if (roleName == null || roleName.isBlank())
            throw new IllegalArgumentException("role name is blank");
    }
}
