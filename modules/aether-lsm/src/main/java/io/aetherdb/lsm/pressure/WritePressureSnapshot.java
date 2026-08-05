package io.aetherdb.lsm.pressure;

import java.util.Set;

public record WritePressureSnapshot(WritePressureState state, Set<WritePressureReason> reasons, long delayMicros, double severity) {
    public WritePressureSnapshot { reasons = Set.copyOf(reasons); }
}
