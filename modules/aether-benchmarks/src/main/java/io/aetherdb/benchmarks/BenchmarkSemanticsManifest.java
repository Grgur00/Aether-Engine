package io.aetherdb.benchmarks;

import java.util.Map;

/** Same-machine semantics manifest required before comparing Aether with another engine. */
public record BenchmarkSemanticsManifest(
        ExternalEngine engine,
        String benchmarkId,
        String machineId,
        String durability,
        int keyBytes,
        int valueBytes,
        int batchSize,
        int threads,
        Map<String, String> workloadShape) {
    /** Validates the comparison semantics fields. */
    public BenchmarkSemanticsManifest {
        if (engine == null
                || benchmarkId == null
                || benchmarkId.isBlank()
                || machineId == null
                || machineId.isBlank()
                || durability == null
                || durability.isBlank()
                || keyBytes <= 0
                || keyBytes > 1 << 20
                || valueBytes < 0
                || valueBytes > 64 * 1024 * 1024
                || batchSize <= 0
                || threads <= 0
                || workloadShape == null
                || workloadShape.size() > 128) {
            throw new IllegalArgumentException("invalid benchmark semantics manifest");
        }
        benchmarkId = benchmarkId.strip();
        machineId = machineId.strip();
        durability = durability.strip().toUpperCase(java.util.Locale.ROOT);
        workloadShape = Map.copyOf(workloadShape);
        for (Map.Entry<String, String> entry : workloadShape.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getKey().length() > 128
                    || entry.getValue() == null
                    || entry.getValue().length() > 4096) {
                throw new IllegalArgumentException("invalid workload shape");
            }
        }
    }

    /** Returns true when this manifest can be compared with another under Chapter 30 rules. */
    public boolean sameSemanticsAs(BenchmarkSemanticsManifest other) {
        return other != null
                && benchmarkId.equals(other.benchmarkId)
                && machineId.equals(other.machineId)
                && durability.equals(other.durability)
                && keyBytes == other.keyBytes
                && valueBytes == other.valueBytes
                && batchSize == other.batchSize
                && threads == other.threads
                && workloadShape.equals(other.workloadShape);
    }
}
