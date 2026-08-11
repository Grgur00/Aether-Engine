package io.aetherdb.lsm.compaction;

import java.util.Arrays;

/** Immutable file metadata needed by the pure compaction picker. */
public final class CompactionFile {
    private final long fileNumber;
    private final int level;
    private final byte[] smallestUserKey;
    private final byte[] largestUserKey;
    private final long fileSize;

    /**
     * Creates validated immutable file metadata.
     *
     * @param fileNumber positive file number
     * @param level owning level
     * @param smallestUserKey inclusive smallest key
     * @param largestUserKey inclusive largest key
     * @param fileSize encoded file bytes
     */
    public CompactionFile(
            long fileNumber,
            int level,
            byte[] smallestUserKey,
            byte[] largestUserKey,
            long fileSize) {
        if (fileNumber <= 0
                || level < 0
                || level >= LevelCompactionConfig.LEVEL_COUNT
                || smallestUserKey == null
                || largestUserKey == null
                || fileSize < 0)
            throw new IllegalArgumentException("invalid compaction file metadata");
        if (Arrays.compareUnsigned(smallestUserKey, largestUserKey) > 0)
            throw new IllegalArgumentException("file key range is reversed");
        this.fileNumber = fileNumber;
        this.level = level;
        this.smallestUserKey = smallestUserKey.clone();
        this.largestUserKey = largestUserKey.clone();
        this.fileSize = fileSize;
    }

    /**
     * Returns the file identity.
     *
     * @return positive file number
     */
    public long fileNumber() {
        return fileNumber;
    }

    /**
     * Returns the owning level.
     *
     * @return level number
     */
    public int level() {
        return level;
    }

    /**
     * Returns the lower key bound.
     *
     * @return defensive smallest-key copy
     */
    public byte[] smallestUserKey() {
        return smallestUserKey.clone();
    }

    /**
     * Returns the upper key bound.
     *
     * @return defensive largest-key copy
     */
    public byte[] largestUserKey() {
        return largestUserKey.clone();
    }

    /**
     * Returns physical size.
     *
     * @return encoded file bytes
     */
    public long fileSize() {
        return fileSize;
    }

    /**
     * Tests inclusive range overlap.
     *
     * @param smallest candidate lower bound
     * @param largest candidate upper bound
     * @return {@code true} when ranges overlap
     */
    public boolean overlaps(byte[] smallest, byte[] largest) {
        return Arrays.compareUnsigned(smallestUserKey, largest) <= 0
                && Arrays.compareUnsigned(smallest, largestUserKey) <= 0;
    }

    /**
     * Tests whether this file may contain a key.
     *
     * @param key logical key
     * @return {@code true} when the key lies within file bounds
     */
    public boolean contains(byte[] key) {
        return Arrays.compareUnsigned(smallestUserKey, key) <= 0
                && Arrays.compareUnsigned(key, largestUserKey) <= 0;
    }
}
