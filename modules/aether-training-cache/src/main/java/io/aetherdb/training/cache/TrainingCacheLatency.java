package io.aetherdb.training.cache;

import java.util.Arrays;

final class TrainingCacheLatency {
    private static final int MAX_SAMPLES = 100_000;
    private final long[] samples = new long[MAX_SAMPLES];
    private int size;
    private long total;

    synchronized void record(long nanos) {
        total++;
        if (size < samples.length) samples[size++] = nanos;
        else samples[(int) (total % samples.length)] = nanos;
    }

    synchronized long percentile(double percentile) {
        if (size == 0) return 0;
        long[] copy = Arrays.copyOf(samples, size);
        Arrays.sort(copy);
        int index = (int) Math.ceil(percentile / 100.0 * copy.length) - 1;
        return copy[Math.max(0, Math.min(index, copy.length - 1))];
    }
}
