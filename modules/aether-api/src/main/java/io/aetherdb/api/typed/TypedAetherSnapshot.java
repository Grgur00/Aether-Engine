package io.aetherdb.api.typed;

/** Stable typed read view that pins its underlying database snapshot until closed. */
public interface TypedAetherSnapshot extends AutoCloseable {
    /**
     * Opens a collection whose reads are evaluated at this snapshot.
     *
     * @param definition collection identity and codecs
     * @param <K> logical key type
     * @param <V> logical value type
     * @return snapshot-bound collection view
     */
    <K, V> TypedAetherCollection<K, V> collection(CollectionDefinition<K, V> definition);

    /** Reports whether the snapshot is closed.
     * @return {@code true} after this snapshot has released its resources */
    boolean isClosed();

    /** Releases resources pinned by this snapshot. */
    @Override
    void close();
}
