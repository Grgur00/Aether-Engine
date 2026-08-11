package io.aetherdb.cache;

/**
 * Point-in-time cache counters and exact resident weight.
 *
 * @param hits successful resident lookups
 * @param misses lookups requiring load or bypass
 * @param loads completed loader invocations
 * @param loadFailures failed loader invocations
 * @param evictions entries removed for capacity
 * @param admissionBypasses blocks returned without admission
 * @param residentBytes charged resident bytes
 * @param pinnedEntries resident entries currently leased
 */
public record BlockCacheMetrics(
        long hits,
        long misses,
        long loads,
        long loadFailures,
        long evictions,
        long admissionBypasses,
        long residentBytes,
        long pinnedEntries) {}
