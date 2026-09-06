package io.aetherdb.training.cache;

/** Storage-selection policy for immutable training-cache values. */
public record TrainingCacheStoragePolicy(Mode mode, int segmentThresholdBytes) {
    public enum Mode { AUTO, INLINE, SEGMENT }

    public TrainingCacheStoragePolicy {
        if (mode == null) throw new NullPointerException("mode");
        if (segmentThresholdBytes < 1) throw new IllegalArgumentException("segment threshold must be positive");
    }

    public static TrainingCacheStoragePolicy auto() {
        return new TrainingCacheStoragePolicy(Mode.AUTO, TrainingCache.INLINE_VALUE_THRESHOLD_BYTES);
    }

    public static TrainingCacheStoragePolicy inline() {
        return new TrainingCacheStoragePolicy(Mode.INLINE, Integer.MAX_VALUE);
    }

    public static TrainingCacheStoragePolicy segment(int thresholdBytes) {
        return new TrainingCacheStoragePolicy(Mode.SEGMENT, thresholdBytes);
    }

    boolean usesSegment(int payloadBytes) {
        return mode == Mode.SEGMENT || mode == Mode.AUTO && payloadBytes >= segmentThresholdBytes;
    }
}
