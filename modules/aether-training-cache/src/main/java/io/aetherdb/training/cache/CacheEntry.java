package io.aetherdb.training.cache;

import java.util.Objects;

/** One immutable cache mutation for a batched publication. */
public record CacheEntry(CacheKey key, byte[] value) {
    public CacheEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        value = value.clone();
    }

    @Override public byte[] value() { return value.clone(); }
}
