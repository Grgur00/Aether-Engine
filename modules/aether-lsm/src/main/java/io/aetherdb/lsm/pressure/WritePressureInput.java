package io.aetherdb.lsm.pressure;

/** One consistent measurement used for a write-admission decision. */
public record WritePressureInput(int immutableMemTables, boolean nativeCapacityAvailable, long retainedWalBytes,
        int levelZeroFiles, long compactionDebtBytes, long usableDiskBytes, long totalDiskBytes,
        boolean diskMeasurementAvailable, boolean backgroundFailed, boolean administrativelyPaused) {
    public WritePressureInput {
        if (immutableMemTables < 0 || retainedWalBytes < 0 || levelZeroFiles < 0 || compactionDebtBytes < 0
                || usableDiskBytes < 0 || totalDiskBytes < 0) throw new IllegalArgumentException("pressure measurements must be non-negative");
    }
}
