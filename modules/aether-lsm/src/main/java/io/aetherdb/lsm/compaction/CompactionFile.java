package io.aetherdb.lsm.compaction;

import java.util.Arrays;

/** Immutable file metadata needed by the pure compaction picker. */
public final class CompactionFile {
    private final long fileNumber;
    private final int level;
    private final byte[] smallestUserKey;
    private final byte[] largestUserKey;
    private final long fileSize;

    public CompactionFile(long fileNumber, int level, byte[] smallestUserKey, byte[] largestUserKey, long fileSize) {
        if (fileNumber <= 0 || level < 0 || level >= LevelCompactionConfig.LEVEL_COUNT ||
                smallestUserKey == null || largestUserKey == null || fileSize < 0)
            throw new IllegalArgumentException("invalid compaction file metadata");
        if (Arrays.compareUnsigned(smallestUserKey, largestUserKey) > 0)
            throw new IllegalArgumentException("file key range is reversed");
        this.fileNumber = fileNumber; this.level = level;
        this.smallestUserKey = smallestUserKey.clone(); this.largestUserKey = largestUserKey.clone();
        this.fileSize = fileSize;
    }
    public long fileNumber() { return fileNumber; }
    public int level() { return level; }
    public byte[] smallestUserKey() { return smallestUserKey.clone(); }
    public byte[] largestUserKey() { return largestUserKey.clone(); }
    public long fileSize() { return fileSize; }
    public boolean overlaps(byte[] smallest, byte[] largest) {
        return Arrays.compareUnsigned(smallestUserKey, largest) <= 0
                && Arrays.compareUnsigned(smallest, largestUserKey) <= 0;
    }
    public boolean contains(byte[] key) {
        return Arrays.compareUnsigned(smallestUserKey, key) <= 0 && Arrays.compareUnsigned(key, largestUserKey) <= 0;
    }
}
