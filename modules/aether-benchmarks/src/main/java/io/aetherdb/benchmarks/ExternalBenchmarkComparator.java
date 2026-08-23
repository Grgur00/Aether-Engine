package io.aetherdb.benchmarks;

import java.util.ArrayList;
import java.util.List;

/** Guarded comparator for Aether versus RocksDB, LevelDB, LMDB, or SQLite. */
public final class ExternalBenchmarkComparator {
    private ExternalBenchmarkComparator() {}

    /** Compares only when same-machine same-durability same-shape semantics match. */
    public static ExternalBenchmarkComparison compare(
            BenchmarkSemanticsManifest aetherManifest,
            BenchmarkResultV1 aetherResult,
            BenchmarkSemanticsManifest externalManifest,
            BenchmarkResultV1 externalResult) {
        if (aetherManifest == null
                || aetherResult == null
                || externalManifest == null
                || externalResult == null) {
            throw new IllegalArgumentException("external comparison inputs are required");
        }
        List<String> blockers = new ArrayList<>();
        if (aetherManifest.engine() != ExternalEngine.AETHER) blockers.add("left manifest is not AETHER");
        if (externalManifest.engine() == ExternalEngine.AETHER) blockers.add("right manifest is not external");
        if (!aetherManifest.sameSemanticsAs(externalManifest)) {
            blockers.add("benchmark semantics mismatch");
        }
        if (!aetherManifest.benchmarkId().equals(aetherResult.benchmarkId())) {
            blockers.add("Aether result benchmark id does not match manifest");
        }
        if (!externalManifest.benchmarkId().equals(externalResult.benchmarkId())) {
            blockers.add("external result benchmark id does not match manifest");
        }
        double ratio = 0.0d;
        if (blockers.isEmpty() && externalResult.throughputOpsPerSecond() > 0.0d) {
            ratio = aetherResult.throughputOpsPerSecond() / externalResult.throughputOpsPerSecond();
        }
        return new ExternalBenchmarkComparison(blockers.isEmpty(), blockers, ratio);
    }
}
