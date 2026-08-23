package io.aetherdb.security.api;

import java.util.Map;
import java.util.Objects;

/** Authenticated security principal after transport/provider validation. */
public record SecurityPrincipal(PrincipalKind kind, String id, Map<String, String> attributes) {
    public SecurityPrincipal {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("principal id is blank");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /** Embedded owner principal used by local embedded mode. */
    public static SecurityPrincipal embeddedOwner() {
        return new SecurityPrincipal(PrincipalKind.EMBEDDED_PROCESS, "embedded:owner", Map.of());
    }
}
