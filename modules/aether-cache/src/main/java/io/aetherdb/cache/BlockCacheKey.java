package io.aetherdb.cache;

/**
 * Stable identity of one immutable block in one SSTable file.
 *
 * @param fileNumber positive SSTable file number
 * @param blockOffset non-negative file offset
 * @param blockLength non-negative encoded block length
 */
public record BlockCacheKey(long fileNumber, long blockOffset, int blockLength) {
    /** Validates file identity and physical block bounds. */
    public BlockCacheKey {
        if (fileNumber <= 0) throw new IllegalArgumentException("fileNumber must be positive");
        if (blockOffset < 0) throw new IllegalArgumentException("blockOffset must be non-negative");
        if (blockLength < 0) throw new IllegalArgumentException("blockLength must be non-negative");
    }
}
