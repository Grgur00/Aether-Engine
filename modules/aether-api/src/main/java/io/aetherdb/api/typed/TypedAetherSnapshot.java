package io.aetherdb.api.typed;
public interface TypedAetherSnapshot extends AutoCloseable{<K,V>TypedAetherCollection<K,V> collection(CollectionDefinition<K,V> definition);boolean isClosed();void close();}
