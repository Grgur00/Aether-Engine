package io.aetherdb.api.typed;

/**
 * One decoded collection entry.
 *
 * @param key non-null logical key
 * @param value non-null logical value
 * @param <K> logical key type
 * @param <V> logical value type
 */
public record TypedKeyValue<K, V>(K key, V value) {
    /** Validates that both parts of the entry are present. */
    public TypedKeyValue {
        if (key == null || value == null) {
            throw new IllegalArgumentException("typed key and value are required");
        }
    }
}
