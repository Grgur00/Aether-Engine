package io.aetherdb.lsm.compaction;

/** Validated leveled-compaction and output-size policy. */
public record LevelCompactionConfig(long level1TargetBytes, int levelSizeMultiplier) {
    public static final int LEVEL_COUNT = 7;
    public static final long MIB = 1024L * 1024;
    public static final long GIB = 1024L * MIB;

    public LevelCompactionConfig {
        if (level1TargetBytes < 128 * MIB || level1TargetBytes > 8 * GIB)
            throw new IllegalArgumentException("L1 target must be between 128 MiB and 8 GiB");
        if (levelSizeMultiplier < 2 || levelSizeMultiplier > 20)
            throw new IllegalArgumentException("level multiplier must be between 2 and 20");
    }

    public static LevelCompactionConfig defaults() { return new LevelCompactionConfig(512 * MIB, 10); }

    public long targetBytes(int level) {
        requireLevel(level, 1, 6);
        long target = level1TargetBytes;
        for (int current = 1; current < level; current++) {
            if (target > Long.MAX_VALUE / levelSizeMultiplier) return Long.MAX_VALUE;
            target *= levelSizeMultiplier;
        }
        return target;
    }

    public long targetOutputFileBytes(int level) {
        requireLevel(level, 1, 6);
        return switch (level) {
            case 1, 2 -> 64 * MIB;
            case 3 -> 128 * MIB;
            case 4 -> 256 * MIB;
            case 5, 6 -> 512 * MIB;
            default -> throw new AssertionError();
        };
    }

    private static void requireLevel(int level, int minimum, int maximum) {
        if (level < minimum || level > maximum) throw new IllegalArgumentException("invalid level: " + level);
    }
}
