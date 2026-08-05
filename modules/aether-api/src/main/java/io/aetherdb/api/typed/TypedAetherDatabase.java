package io.aetherdb.api.typed;
public interface TypedAetherDatabase extends AutoCloseable{<K,V>TypedAetherCollection<K,V> collection(CollectionDefinition<K,V> definition);TypedWriteBatch batch();TypedWriteResult write(TypedWriteBatch batch);TypedAetherSnapshot snapshot();boolean isClosed();void close();}
