package io.aetherdb.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable write admission and durability options.
 * @param durabilityMode requested persistence barrier
 * @param admissionTimeout maximum time to wait for write-path admission
 * @param failFastOnBackpressure whether to reject immediately when capacity is unavailable
 */
public record WriteOptions(DurabilityMode durabilityMode, Duration admissionTimeout, boolean failFastOnBackpressure) {
    /** Validates required values and timeout bounds. */
    public WriteOptions {
        Objects.requireNonNull(durabilityMode, "durabilityMode"); Objects.requireNonNull(admissionTimeout, "admissionTimeout");
        if (admissionTimeout.isNegative()) throw new IllegalArgumentException("admission timeout must be non-negative");
    }
    /** Returns balanced default options.
     * @return group-sync options with a 30-second admission timeout */
    public static WriteOptions defaults() { return new WriteOptions(DurabilityMode.GROUP_SYNC, Duration.ofSeconds(30), false); }
}
