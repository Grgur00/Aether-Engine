package io.aetherdb.lsm.pressure;

import io.aetherdb.lsm.compaction.LevelCompactionConfig;

/** Tunable write-pressure thresholds used before sequence allocation or WAL mutation. */
public record WritePressurePolicy(
        int immutableMemtableSlow,
        int immutableMemtableStop,
        int levelZeroSlow,
        int levelZeroStop,
        long walBytesSlow,
        long walBytesStop,
        long compactionDebtSlow,
        long compactionDebtStop) {
    public WritePressurePolicy {
        if (immutableMemtableSlow < 1
                || immutableMemtableStop <= immutableMemtableSlow
                || levelZeroSlow < 1
                || levelZeroStop <= levelZeroSlow
                || walBytesSlow < 1
                || walBytesStop <= walBytesSlow
                || compactionDebtSlow < 1
                || compactionDebtStop <= compactionDebtSlow) {
            throw new IllegalArgumentException("invalid write pressure policy");
        }
    }

    public static WritePressurePolicy defaults() {
        return new WritePressurePolicy(
                2,
                4,
                12,
                20,
                512 * LevelCompactionConfig.MIB,
                2 * LevelCompactionConfig.GIB,
                2 * LevelCompactionConfig.GIB,
                8 * LevelCompactionConfig.GIB);
    }

    public static WritePressurePolicy forImmutableLimit(int immutableMemtableStop) {
        return new WritePressurePolicy(
                Math.max(1, immutableMemtableStop / 2),
                immutableMemtableStop,
                defaults().levelZeroSlow(),
                defaults().levelZeroStop(),
                defaults().walBytesSlow(),
                defaults().walBytesStop(),
                defaults().compactionDebtSlow(),
                defaults().compactionDebtStop());
    }
}
