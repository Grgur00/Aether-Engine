package io.aetherdb.lsm.compaction;

import java.util.Objects;

/** Exact Chapter 14 level score and debt calculations. */
public final class CompactionScoreCalculator {
    private final LevelCompactionConfig config;

    /**
     * Creates a calculator for one level policy.
     *
     * @param config compaction sizing configuration
     */
    public CompactionScoreCalculator(LevelCompactionConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Calculates L0 through L5 compaction scores.
     *
     * @param version immutable file inventory
     * @return six level scores
     */
    public CompactionScores calculate(VersionInventory version) {
        double[] scores = new double[6];
        scores[0] = version.level(0).size() / 4.0;
        for (int level = 1; level <= 5; level++)
            scores[level] = (double) version.levelBytes(level) / config.targetBytes(level);
        return new CompactionScores(scores);
    }

    /**
     * Estimates excess bytes represented by compaction backlog.
     *
     * @param version immutable file inventory
     * @return saturated debt estimate
     */
    public long estimatedDebtBytes(VersionInventory version) {
        long debt = 0;
        ListMath l0 = new ListMath(version.level(0).size(), version.levelBytes(0));
        if (l0.count > 4)
            debt = saturatingAdd(debt, saturatingMultiply(l0.count - 4, l0.average()));
        for (int level = 1; level <= 5; level++)
            debt =
                    saturatingAdd(
                            debt,
                            Math.max(0, version.levelBytes(level) - config.targetBytes(level)));
        return debt;
    }

    private static long saturatingAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static long saturatingMultiply(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    private record ListMath(long count, long bytes) {
        long average() {
            return count == 0 ? 0 : bytes / count;
        }
    }
}
