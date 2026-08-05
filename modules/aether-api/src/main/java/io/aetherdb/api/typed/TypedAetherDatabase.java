package io.aetherdb.api.typed;

public interface TypedAetherDatabase extends AutoCloseable {
    <K, V> TypedAetherCollection<K, V> collection(CollectionDefinition<K, V> definition);

    <K, V> TypedAetherCollection<K, V> defineCollection(
            CollectionId id, String name, Class<K> keyType, Class<V> valueType);

    TypedWriteBatch batch();
    TypedWriteResult write(TypedWriteBatch batch);
    TypedAetherSnapshot snapshot();
    boolean isClosed();
    @Override void close();
}
