package io.aetherdb.lsm.pressure;

import io.aetherdb.lsm.compaction.LevelCompactionConfig;
import java.util.EnumSet;
import java.util.Set;

/** Pure write-pressure evaluator; admission waits happen before sequence or WAL mutation. */
public final class WritePressureController {
    private static final long WAL_SLOW = 512 * LevelCompactionConfig.MIB;
    private static final long WAL_STOP = 2 * LevelCompactionConfig.GIB;
    private static final long DEBT_SLOW = 2 * LevelCompactionConfig.GIB;
    private static final long DEBT_STOP = 8 * LevelCompactionConfig.GIB;

    public WritePressureSnapshot evaluate(WritePressureInput input) {
        Set<WritePressureReason> reasons = EnumSet.noneOf(WritePressureReason.class);
        boolean failed = input.backgroundFailed();
        boolean stopped = input.immutableMemTables() >= 4 || !input.nativeCapacityAvailable()
                || input.retainedWalBytes() >= WAL_STOP || input.levelZeroFiles() >= 20
                || input.compactionDebtBytes() >= DEBT_STOP || input.administrativelyPaused();
        if (input.backgroundFailed()) reasons.add(WritePressureReason.BACKGROUND_FAILURE);
        if (input.administrativelyPaused()) reasons.add(WritePressureReason.ADMINISTRATIVE_PAUSE);
        if (input.immutableMemTables() >= 2) reasons.add(WritePressureReason.IMMUTABLE_MEMTABLES);
        if (!input.nativeCapacityAvailable()) reasons.add(WritePressureReason.NATIVE_CAPACITY);
        if (input.retainedWalBytes() >= WAL_SLOW) reasons.add(WritePressureReason.WAL_BYTES);
        if (input.levelZeroFiles() >= 12) reasons.add(WritePressureReason.LEVEL_ZERO_FILES);
        if (input.compactionDebtBytes() >= DEBT_SLOW) reasons.add(WritePressureReason.COMPACTION_DEBT);

        if (input.diskMeasurementAvailable()) {
            long slowDisk = Math.max(10 * LevelCompactionConfig.GIB, percentage(input.totalDiskBytes(), 15));
            long stopDisk = Math.max(2 * LevelCompactionConfig.GIB, percentage(input.totalDiskBytes(), 5));
            if (input.usableDiskBytes() < slowDisk) reasons.add(WritePressureReason.DISK_SPACE);
            if (input.usableDiskBytes() < stopDisk) stopped = true;
        }
        if (failed) return new WritePressureSnapshot(WritePressureState.FAILED, reasons, 0, 1);
        if (stopped) return new WritePressureSnapshot(WritePressureState.STOPPED_RETRYABLE, reasons, 0, 1);
        double severity = maximumSeverity(input);
        if (reasons.isEmpty()) return new WritePressureSnapshot(WritePressureState.NORMAL, reasons, 0, 0);
        long delay = Math.min(10_000, Math.round(100 + severity * severity * 9_900));
        return new WritePressureSnapshot(WritePressureState.SLOWDOWN, reasons, delay, severity);
    }

    private static double maximumSeverity(WritePressureInput input) {
        double severity = 0;
        severity = Math.max(severity, ratio(input.immutableMemTables(), 2, 4));
        severity = Math.max(severity, ratio(input.retainedWalBytes(), WAL_SLOW, WAL_STOP));
        severity = Math.max(severity, ratio(input.levelZeroFiles(), 12, 20));
        severity = Math.max(severity, ratio(input.compactionDebtBytes(), DEBT_SLOW, DEBT_STOP));
        if (input.diskMeasurementAvailable()) {
            long slow = Math.max(10 * LevelCompactionConfig.GIB, percentage(input.totalDiskBytes(), 15));
            long stop = Math.max(2 * LevelCompactionConfig.GIB, percentage(input.totalDiskBytes(), 5));
            severity = Math.max(severity, 1 - ratio(input.usableDiskBytes(), stop, slow));
        }
        return Math.max(0, Math.min(1, severity));
    }
    private static double ratio(long value, long start, long stop) { return Math.max(0, Math.min(1, (double) (value - start) / (stop - start))); }
    private static long percentage(long total, int percent) { return total / 100 * percent + total % 100 * percent / 100; }
}
