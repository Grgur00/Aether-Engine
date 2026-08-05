package io.aetherdb.lsm.pressure;

import java.util.Set;

/**
 * Immutable write-admission decision and its contributing reasons.
 * @param state resulting admission state
 * @param reasons immutable contributing reasons
 * @param delayMicros recommended slowdown delay
 * @param severity normalized pressure from zero to one
 */
public record WritePressureSnapshot(WritePressureState state, Set<WritePressureReason> reasons, long delayMicros, double severity) {
    /** Copies the reason set. */
    public WritePressureSnapshot { reasons = Set.copyOf(reasons); }
}
