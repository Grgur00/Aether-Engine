package io.aetherdb.api.typed;
import java.util.UUID;
public record CollectionId(UUID value){public CollectionId{if(value==null||value.equals(new UUID(0,0)))throw new IllegalArgumentException("collection ID must be nonzero");}public static CollectionId of(String value){return new CollectionId(UUID.fromString(value));}}
