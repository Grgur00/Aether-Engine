package io.aetherdb.api.typed;

/** Mutable builder for a group of typed writes committed atomically. */
public interface TypedWriteBatch {
    /**
     * Appends a put operation.
     *
     * @param collection target collection
     * @param key logical key
     * @param value logical value
     * @param <K> logical key type
     * @param <V> logical value type
     * @return this batch
     */
    <K, V> TypedWriteBatch put(TypedAetherCollection<K, V> collection, K key, V value);

    /**
     * Appends a delete operation.
     *
     * @param collection target collection
     * @param key logical key
     * @param <K> logical key type
     * @param <V> logical value type
     * @return this batch
     */
    <K, V> TypedWriteBatch delete(TypedAetherCollection<K, V> collection, K key);

    /** Returns the batch size.
     * @return number of operations currently accumulated */
    int operationCount();
}
