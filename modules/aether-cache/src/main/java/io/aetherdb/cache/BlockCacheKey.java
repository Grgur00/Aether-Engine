package io.aetherdb.cache;

/** Stable identity of one immutable block in one SSTable file. */
public record BlockCacheKey(long fileNumber, long blockOffset, int blockLength) {
    public BlockCacheKey {
        if (fileNumber <= 0) throw new IllegalArgumentException("fileNumber must be positive");
        if (blockOffset < 0) throw new IllegalArgumentException("blockOffset must be non-negative");
        if (blockLength < 0) throw new IllegalArgumentException("blockLength must be non-negative");
    }
}
