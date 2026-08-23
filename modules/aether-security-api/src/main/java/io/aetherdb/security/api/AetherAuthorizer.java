package io.aetherdb.security.api;

/** Evaluates whether a principal may perform an operation on a resource. */
@FunctionalInterface
public interface AetherAuthorizer {
    AuthorizationDecision authorize(
            SecurityPrincipal principal, AetherPermission permission, ResourceId resource);
}
