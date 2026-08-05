package io.aetherdb.api.typed;

/** High-level, type-safe database facade built on durable collection definitions. */
public interface TypedAetherDatabase extends AutoCloseable {
    /**
     * Opens a typed view using an explicit collection definition.
     *
     * @param definition codecs and identity governing the collection
     * @param <K> logical key type
     * @param <V> logical value type
     * @return collection view bound to this database
     */
    <K, V> TypedAetherCollection<K, V> collection(CollectionDefinition<K, V> definition);

    /**
     * Defines or opens a collection by resolving registered codecs for its Java types.
     *
     * @param id durable collection identity
     * @param name human-readable collection name
     * @param keyType key class used for codec resolution
     * @param valueType value class used for codec resolution
     * @param <K> logical key type
     * @param <V> logical value type
     * @return collection view bound to this database
     */
    <K, V> TypedAetherCollection<K, V> defineCollection(
            CollectionId id, String name, Class<K> keyType, Class<V> valueType);

    /** Creates a write batch.
     * @return a new empty atomic write batch */
    TypedWriteBatch batch();

    /**
     * Atomically submits all operations in a batch.
     *
     * @param batch batch created for this database
     * @return terminal or indeterminate write result
     */
    TypedWriteResult write(TypedWriteBatch batch);

    /** Captures a read snapshot.
     * @return a stable snapshot that the caller must close */
    TypedAetherSnapshot snapshot();

    /** Reports whether the database is closed.
     * @return {@code true} after this database has been closed */
    boolean isClosed();

    /** Releases database resources and rejects subsequent operations. */
    @Override
    void close();
}
