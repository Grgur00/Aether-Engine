package io.aetherdb.memtable.skiplist;

/** Deterministic SplitMix64 height generator with 1/4 promotion probability. */
public final class SkipListHeightGenerator {
    public static final int MAX_HEIGHT = 20;
    private long state;

    public SkipListHeightGenerator(long seed) {
        state = seed;
    }

    public int nextHeight() {
        int height = 1;
        long bits = nextLong();
        while (height < MAX_HEIGHT && (bits & 3L) == 0) {
            height++;
            bits >>>= 2;
        }
        return height;
    }

    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long value = state;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
