package io.aetherdb.training.cache;

/** Point-in-time counters for one training cache instance. */
public record TrainingCacheMetrics(long hits, long misses, long corruptEntries, long bytesServed,
        long bytesWritten, long evictions, long residentEntries, long diskBytes,
        long getP50Nanos, long getP95Nanos, long getP99Nanos,
        long putP50Nanos, long putP95Nanos, long putP99Nanos) {}
