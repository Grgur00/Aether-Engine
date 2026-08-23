package io.aetherdb.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RbacAuthorizerTest {
    @Test
    void deniesByDefault() {
        RbacAuthorizer authorizer = new RbacAuthorizer(RbacPolicy.empty());

        AuthorizationDecision decision =
                authorizer.authorize(
                        SecurityPrincipal.embeddedOwner(),
                        AetherPermission.of("collection.read"),
                        new ResourceId("collection", "c1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("RBAC_DENY_DEFAULT");
    }

    @Test
    void allowsOnlyGrantedPermissionAndResource() {
        SecurityPrincipal principal = SecurityPrincipal.embeddedOwner();
        RoleDefinition role =
                new RoleDefinition(
                        "reader",
                        Set.of(
                                new RoleGrant(
                                        AetherPermission.of("collection.read"),
                                        new ResourceId("collection", "c1"))));
        RbacAuthorizer authorizer =
                new RbacAuthorizer(
                        new RbacPolicy(
                                Map.of(role.name(), role),
                                java.util.List.of(new RoleBinding(principal.id(), role.name()))));

        assertThat(
                        authorizer
                                .authorize(
                                        principal,
                                        AetherPermission.of("collection.read"),
                                        new ResourceId("collection", "c1"))
                                .allowed())
                .isTrue();
        assertThat(
                        authorizer
                                .authorize(
                                        principal,
                                        AetherPermission.of("collection.write"),
                                        new ResourceId("collection", "c1"))
                                .allowed())
                .isFalse();
        assertThat(
                        authorizer
                                .authorize(
                                        principal,
                                        AetherPermission.of("collection.read"),
                                        new ResourceId("collection", "c2"))
                                .allowed())
                .isFalse();
    }

    @Test
    void rejectsUnknownRoleBinding() {
        assertThatThrownBy(
                        () ->
                                new RbacPolicy(
                                        Map.of(),
                                        java.util.List.of(new RoleBinding("client:1", "missing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown role");
    }
}
