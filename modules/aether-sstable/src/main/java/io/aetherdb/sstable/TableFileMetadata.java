package io.aetherdb.sstable;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Immutable identity, bounds, and accounting for one published SSTable.
 *
 * @param path table path
 * @param databaseId owning database identity
 * @param fileNumber positive file number
 * @param fileSize exact physical bytes
 * @param entryCount internal-entry count
 * @param dataBlockCount data-block count
 * @param smallestInternalKey first internal key
 * @param largestInternalKey last internal key
 * @param smallestSequence minimum contained sequence
 * @param largestSequence maximum contained sequence
 * @param rawKeyBytes uncompressed internal-key bytes
 * @param rawValueBytes uncompressed value bytes
 */
public record TableFileMetadata(
        Path path, UUID databaseId, long fileNumber, long fileSize, long entryCount,
        int dataBlockCount, byte[] smallestInternalKey, byte[] largestInternalKey,
        long smallestSequence, long largestSequence, long rawKeyBytes, long rawValueBytes) {
    /** Validates fields and takes defensive key copies. */
    public TableFileMetadata {
        if (path == null || databaseId == null || fileNumber <= 0 || fileSize <= 0 || entryCount <= 0
                || dataBlockCount <= 0 || smallestInternalKey == null || largestInternalKey == null
                || smallestSequence <= 0 || largestSequence < smallestSequence
                || rawKeyBytes < 0 || rawValueBytes < 0) throw new IllegalArgumentException("invalid table metadata");
        smallestInternalKey = smallestInternalKey.clone();
        largestInternalKey = largestInternalKey.clone();
    }
    /**
     * Returns the first internal key.
     *
     * @return defensive smallest internal-key bytes
     */
    @Override public byte[] smallestInternalKey() { return smallestInternalKey.clone(); }
    /**
     * Returns the last internal key.
     *
     * @return defensive largest internal-key bytes
     */
    @Override public byte[] largestInternalKey() { return largestInternalKey.clone(); }
    /**
     * Returns the lower user-key bound.
     *
     * @return smallest user-key bytes
     */
    public byte[] smallestUserKey() { return InternalKey.decode(smallestInternalKey).userKey(); }
    /**
     * Returns the upper user-key bound.
     *
     * @return largest user-key bytes
     */
    public byte[] largestUserKey() { return InternalKey.decode(largestInternalKey).userKey(); }
}
