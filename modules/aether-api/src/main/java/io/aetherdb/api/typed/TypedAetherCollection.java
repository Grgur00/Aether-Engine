package io.aetherdb.api.typed;

import java.util.List;

/**
 * Type-safe view of one logical collection in an Aether database.
 *
 * @param <K> logical key type
 * @param <V> logical value type
 */
public interface TypedAetherCollection<K, V> {
    /**
     * Returns the collection definition.
     *
     * @return immutable definition governing keys, values, and capabilities
     */
    CollectionDefinition<K, V> definition();

    /**
     * Reads the value associated with a key.
     *
     * @param key logical key
     * @return a found or not-found result
     */
    ReadResult<V> get(K key);

    /**
     * Inserts or replaces a value.
     *
     * @param key logical key
     * @param value logical value
     * @return terminal write outcome
     */
    TypedWriteResult put(K key, V value);

    /**
     * Deletes a key if it exists.
     *
     * @param key logical key
     * @return terminal write outcome
     */
    TypedWriteResult delete(K key);

    /**
     * Materializes all visible entries in key order when the codec is ordered.
     *
     * @return immutable or independently mutable result list, never {@code null}
     */
    List<TypedKeyValue<K, V>> scanAll();
}
