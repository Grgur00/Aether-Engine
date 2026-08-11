package io.aetherdb.sstable.manifest;

import io.aetherdb.sstable.InternalKey;

import java.util.Arrays;

/**
 * Immutable manifest description of one live SSTable.
 *
 * @param fileNumber durable table file number
 * @param level LSM level from zero through six
 * @param fileSize exact physical file bytes
 * @param entryCount number of internal entries
 * @param smallestSequence minimum contained sequence
 * @param largestSequence maximum contained sequence
 * @param smallestInternalKey first internal key
 * @param largestInternalKey last internal key
 */
public record ManifestFileMetadata(
        long fileNumber,
        int level,
        long fileSize,
        long entryCount,
        long smallestSequence,
        long largestSequence,
        byte[] smallestInternalKey,
        byte[] largestInternalKey) {
    /** Validates persisted fields and takes defensive key copies. */
    public ManifestFileMetadata {
        if (fileNumber <= 0
                || level < 0
                || level > 6
                || fileSize <= 0
                || entryCount <= 0
                || smallestSequence <= 0
                || largestSequence < smallestSequence
                || smallestInternalKey == null
                || largestInternalKey == null) {
            throw new IllegalArgumentException("invalid manifest file metadata");
        }
        InternalKey smallest = InternalKey.decode(smallestInternalKey);
        InternalKey largest = InternalKey.decode(largestInternalKey);
        if (smallest.compareTo(largest) > 0
                || smallest.sequence() < smallestSequence
                || smallest.sequence() > largestSequence
                || largest.sequence() < smallestSequence
                || largest.sequence() > largestSequence) {
            throw new IllegalArgumentException("invalid manifest key bounds");
        }
        smallestInternalKey = smallestInternalKey.clone();
        largestInternalKey = largestInternalKey.clone();
    }

    /**
     * Returns a defensive copy of the lower internal-key bound.
     *
     * @return copied first internal key
     */
    @Override
    public byte[] smallestInternalKey() {
        return smallestInternalKey.clone();
    }

    /**
     * Returns a defensive copy of the upper internal-key bound.
     *
     * @return copied last internal key
     */
    @Override
    public byte[] largestInternalKey() {
        return largestInternalKey.clone();
    }

    /**
     * Returns the lower user-key bound.
     *
     * @return copied first user key
     */
    public byte[] smallestUserKey() {
        return InternalKey.decode(smallestInternalKey).userKey();
    }

    /**
     * Returns the upper user-key bound.
     *
     * @return copied last user key
     */
    public byte[] largestUserKey() {
        return InternalKey.decode(largestInternalKey).userKey();
    }

    /**
     * Compares all persisted fields, including byte-array contents.
     *
     * @param other candidate metadata
     * @return {@code true} when every persisted field is equal
     */
    public boolean contentEquals(ManifestFileMetadata other) {
        return other != null
                && fileNumber == other.fileNumber
                && level == other.level
                && fileSize == other.fileSize
                && entryCount == other.entryCount
                && smallestSequence == other.smallestSequence
                && largestSequence == other.largestSequence
                && Arrays.equals(smallestInternalKey, other.smallestInternalKey)
                && Arrays.equals(largestInternalKey, other.largestInternalKey);
    }
}
