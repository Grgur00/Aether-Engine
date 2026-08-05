package io.aetherdb.api;

import java.time.Duration;
import java.util.Objects;

/** Immutable write admission and durability options. */
public record WriteOptions(DurabilityMode durabilityMode, Duration admissionTimeout, boolean failFastOnBackpressure) {
    public WriteOptions {
        Objects.requireNonNull(durabilityMode, "durabilityMode"); Objects.requireNonNull(admissionTimeout, "admissionTimeout");
        if (admissionTimeout.isNegative()) throw new IllegalArgumentException("admission timeout must be non-negative");
    }
    public static WriteOptions defaults() { return new WriteOptions(DurabilityMode.GROUP_SYNC, Duration.ofSeconds(30), false); }
}
