package io.aetherdb.api.typed;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable, non-zero identifier stored with every collection entry.
 *
 * @param value non-zero UUID
 */
public record CollectionId(UUID value) {
    private static final byte[] NAME_NAMESPACE =
            "io.aetherdb.collection.v1\0".getBytes(StandardCharsets.UTF_8);

    /**
     * Creates a collection identifier.
     *
     * @param value non-zero UUID
     * @throws IllegalArgumentException if {@code value} is null or zero
     */
    public CollectionId {
        if (value == null || value.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("collection ID must be nonzero");
        }
    }

    /**
     * Parses a canonical UUID as a collection identifier.
     *
     * @param value UUID text
     * @return parsed collection identifier
     */
    public static CollectionId of(String value) {
        return new CollectionId(UUID.fromString(value));
    }

    /**
     * Derives a stable collection identifier from its application-visible name.
     *
     * <p>The mapping is case-sensitive and frozen as the RFC 4122 version-three UUID of the UTF-8
     * bytes {@code "io.aetherdb.collection.v1\0" + name}. Renaming a collection therefore creates a
     * different logical keyspace. Use an explicit {@link CollectionId} when identity must remain
     * independent of the name.
     *
     * @param name non-blank collection name of at most 128 UTF-8 bytes
     * @return deterministic collection identifier
     */
    public static CollectionId fromName(String name) {
        Objects.requireNonNull(name, "name");
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        if (name.isBlank() || encoded.length > 128)
            throw new IllegalArgumentException("invalid collection name");
        byte[] namespaced = new byte[NAME_NAMESPACE.length + encoded.length];
        System.arraycopy(NAME_NAMESPACE, 0, namespaced, 0, NAME_NAMESPACE.length);
        System.arraycopy(encoded, 0, namespaced, NAME_NAMESPACE.length, encoded.length);
        return new CollectionId(UUID.nameUUIDFromBytes(namespaced));
    }
}
