package io.aetherdb.api.typed;
import java.util.NoSuchElementException;import java.util.Optional;
public sealed interface ReadResult<V> permits ReadResult.Found,ReadResult.NotFound{Optional<V> value();default V requireValue(){return value().orElseThrow(()->new NoSuchElementException("value not found"));}record Found<V>(V found)implements ReadResult<V>{public Found{if(found==null)throw new IllegalArgumentException("value is null");}public Optional<V> value(){return Optional.of(found);}}record NotFound<V>()implements ReadResult<V>{public Optional<V> value(){return Optional.empty();}}}
