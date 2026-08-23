package io.aetherdb.lsm.pressure;

import java.util.EnumSet;
import java.util.Set;

/** Pure write-pressure evaluator; admission waits happen before sequence or WAL mutation. */
public final class WritePressureController {
    private final WritePressurePolicy policy;

    /** Creates a stateless pressure evaluator. */
    public WritePressureController() {
        this(WritePressurePolicy.defaults());
    }

    /** Creates a pressure evaluator with explicit thresholds. */
    public WritePressureController(WritePressurePolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    /**
     * Evaluates one consistent set of pressure measurements.
     *
     * @param input current storage measurements
     * @return admission state, reasons, and optional delay
     */
    public WritePressureSnapshot evaluate(WritePressureInput input) {
        Set<WritePressureReason> reasons = EnumSet.noneOf(WritePressureReason.class);
        boolean failed = input.backgroundFailed();
        boolean stopped =
                input.immutableMemTables() >= policy.immutableMemtableStop()
                        || !input.nativeCapacityAvailable()
                        || input.retainedWalBytes() >= policy.walBytesStop()
                        || input.levelZeroFiles() >= policy.levelZeroStop()
                        || input.compactionDebtBytes() >= policy.compactionDebtStop()
                        || input.administrativelyPaused();
        if (input.backgroundFailed()) reasons.add(WritePressureReason.BACKGROUND_FAILURE);
        if (input.administrativelyPaused()) reasons.add(WritePressureReason.ADMINISTRATIVE_PAUSE);
        if (input.immutableMemTables() >= policy.immutableMemtableSlow())
            reasons.add(WritePressureReason.IMMUTABLE_MEMTABLES);
        if (!input.nativeCapacityAvailable()) reasons.add(WritePressureReason.NATIVE_CAPACITY);
        if (input.retainedWalBytes() >= policy.walBytesSlow())
            reasons.add(WritePressureReason.WAL_BYTES);
        if (input.levelZeroFiles() >= policy.levelZeroSlow())
            reasons.add(WritePressureReason.LEVEL_ZERO_FILES);
        if (input.compactionDebtBytes() >= policy.compactionDebtSlow())
            reasons.add(WritePressureReason.COMPACTION_DEBT);

        if (input.diskMeasurementAvailable()) {
            long slowDisk =
                    Math.max(
                            10 * io.aetherdb.lsm.compaction.LevelCompactionConfig.GIB,
                            percentage(input.totalDiskBytes(), 15));
            long stopDisk =
                    Math.max(
                            2 * io.aetherdb.lsm.compaction.LevelCompactionConfig.GIB,
                            percentage(input.totalDiskBytes(), 5));
            if (input.usableDiskBytes() < slowDisk) reasons.add(WritePressureReason.DISK_SPACE);
            if (input.usableDiskBytes() < stopDisk) stopped = true;
        }
        if (failed) return new WritePressureSnapshot(WritePressureState.FAILED, reasons, 0, 1);
        if (stopped)
            return new WritePressureSnapshot(WritePressureState.STOPPED_RETRYABLE, reasons, 0, 1);
        double severity = maximumSeverity(input, policy);
        if (reasons.isEmpty())
            return new WritePressureSnapshot(WritePressureState.NORMAL, reasons, 0, 0);
        long delay = Math.min(10_000, Math.round(100 + severity * severity * 9_900));
        return new WritePressureSnapshot(WritePressureState.SLOWDOWN, reasons, delay, severity);
    }

    private static double maximumSeverity(WritePressureInput input, WritePressurePolicy policy) {
        double severity = 0;
        severity =
                Math.max(
                        severity,
                        ratio(
                                input.immutableMemTables(),
                                policy.immutableMemtableSlow(),
                                policy.immutableMemtableStop()));
        severity =
                Math.max(
                        severity,
                        ratio(input.retainedWalBytes(), policy.walBytesSlow(), policy.walBytesStop()));
        severity = Math.max(severity, ratio(input.levelZeroFiles(), policy.levelZeroSlow(), policy.levelZeroStop()));
        severity =
                Math.max(
                        severity,
                        ratio(
                                input.compactionDebtBytes(),
                                policy.compactionDebtSlow(),
                                policy.compactionDebtStop()));
        if (input.diskMeasurementAvailable()) {
            long slow =
                    Math.max(
                            10 * io.aetherdb.lsm.compaction.LevelCompactionConfig.GIB,
                            percentage(input.totalDiskBytes(), 15));
            long stop =
                    Math.max(
                            2 * io.aetherdb.lsm.compaction.LevelCompactionConfig.GIB,
                            percentage(input.totalDiskBytes(), 5));
            severity = Math.max(severity, 1 - ratio(input.usableDiskBytes(), stop, slow));
        }
        return Math.max(0, Math.min(1, severity));
    }

    private static double ratio(long value, long start, long stop) {
        return Math.max(0, Math.min(1, (double) (value - start) / (stop - start)));
    }

    private static long percentage(long total, int percent) {
        return total / 100 * percent + total % 100 * percent / 100;
    }
}
