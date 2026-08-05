package io.aetherdb.lsm.pressure;

/**
 * One consistent measurement used for a write-admission decision.
 * @param immutableMemTables immutable MemTables awaiting flush
 * @param nativeCapacityAvailable whether native allocation can proceed
 * @param retainedWalBytes WAL bytes retained for recovery
 * @param levelZeroFiles current L0 file count
 * @param compactionDebtBytes estimated compaction debt
 * @param usableDiskBytes usable storage bytes
 * @param totalDiskBytes total storage bytes
 * @param diskMeasurementAvailable whether disk measurements are reliable
 * @param backgroundFailed whether required background work failed
 * @param administrativelyPaused whether writes are administratively paused
 */
public record WritePressureInput(int immutableMemTables, boolean nativeCapacityAvailable, long retainedWalBytes,
        int levelZeroFiles, long compactionDebtBytes, long usableDiskBytes, long totalDiskBytes,
        boolean diskMeasurementAvailable, boolean backgroundFailed, boolean administrativelyPaused) {
    /** Validates that all measured quantities are non-negative. */
    public WritePressureInput {
        if (immutableMemTables < 0 || retainedWalBytes < 0 || levelZeroFiles < 0 || compactionDebtBytes < 0
                || usableDiskBytes < 0 || totalDiskBytes < 0) throw new IllegalArgumentException("pressure measurements must be non-negative");
    }
}
