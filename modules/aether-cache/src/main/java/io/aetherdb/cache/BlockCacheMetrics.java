package io.aetherdb.cache;

/** Point-in-time cache counters and exact resident weight. */
public record BlockCacheMetrics(
        long hits, long misses, long loads, long loadFailures, long evictions,
        long admissionBypasses, long residentBytes, long pinnedEntries) {}
