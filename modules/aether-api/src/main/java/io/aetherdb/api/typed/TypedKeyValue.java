package io.aetherdb.api.typed;
public record TypedKeyValue<K,V>(K key,V value){public TypedKeyValue{if(key==null||value==null)throw new IllegalArgumentException("typed key and value are required");}}
