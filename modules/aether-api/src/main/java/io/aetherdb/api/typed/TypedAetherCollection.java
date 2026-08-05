package io.aetherdb.api.typed;
import java.util.List;
public interface TypedAetherCollection<K,V>{CollectionDefinition<K,V> definition();ReadResult<V> get(K key);TypedWriteResult put(K key,V value);TypedWriteResult delete(K key);List<TypedKeyValue<K,V>> scanAll();}
