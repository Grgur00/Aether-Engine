package io.aetherdb.security.api;

import java.util.Objects;

/** Stable authorization resource identity. */
public record ResourceId(String kind, String id) {
    public static final ResourceId CLUSTER = new ResourceId("cluster", "*");
    public static final ResourceId ANY = new ResourceId("*", "*");

    public ResourceId {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("blank resource kind");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("blank resource id");
    }

    /** Returns whether this scope covers the requested resource. */
    public boolean covers(ResourceId requested) {
        Objects.requireNonNull(requested, "requested");
        return (kind.equals("*") || kind.equals(requested.kind))
                && (id.equals("*") || id.equals(requested.id));
    }
}
