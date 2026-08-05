package io.aetherdb.lsm.compaction;

import java.util.List;

/** Immutable input selection produced by the pure compaction picker. */
public record CompactionPlan(
        int inputLevel, int outputLevel, List<CompactionFile> primaryInputs,
        List<CompactionFile> outputLevelInputs, List<CompactionFile> grandparents,
        byte[] smallestUserKey, byte[] largestUserKey, long oldestSnapshotSequence,
        long targetOutputFileBytes, long grandparentOverlapLimit, long estimatedInputBytes,
        CompactionReason reason, double score, byte[] pointerUpdate) {
    public CompactionPlan {
        primaryInputs = List.copyOf(primaryInputs); outputLevelInputs = List.copyOf(outputLevelInputs); grandparents = List.copyOf(grandparents);
        smallestUserKey = smallestUserKey.clone(); largestUserKey = largestUserKey.clone(); pointerUpdate = pointerUpdate.clone();
        if (primaryInputs.isEmpty() || outputLevel != inputLevel + 1 || oldestSnapshotSequence < 0)
            throw new IllegalArgumentException("invalid compaction plan");
    }
    @Override public byte[] smallestUserKey() { return smallestUserKey.clone(); }
    @Override public byte[] largestUserKey() { return largestUserKey.clone(); }
    @Override public byte[] pointerUpdate() { return pointerUpdate.clone(); }
}
