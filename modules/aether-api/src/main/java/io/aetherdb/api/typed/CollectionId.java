package io.aetherdb.api.typed;

import java.util.UUID;

/**
 * Stable, non-zero identifier stored with every collection entry.
 *
 * @param value non-zero UUID
 */
public record CollectionId(UUID value) {
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
}
