package io.aetherdb.api.typed;
public interface TypedWriteBatch{<K,V>TypedWriteBatch put(TypedAetherCollection<K,V> collection,K key,V value);<K,V>TypedWriteBatch delete(TypedAetherCollection<K,V> collection,K key);int operationCount();}
