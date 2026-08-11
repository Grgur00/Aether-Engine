package io.aetherdb.lsm.compaction;

import java.util.List;

/**
 * Immutable input selection produced by the pure compaction picker.
 *
 * @param inputLevel source level
 * @param outputLevel destination level
 * @param primaryInputs selected source-level files
 * @param outputLevelInputs overlapping destination files
 * @param grandparents overlapping files one level below the destination
 * @param smallestUserKey inclusive plan lower bound
 * @param largestUserKey inclusive plan upper bound
 * @param oldestSnapshotSequence oldest sequence that must remain visible
 * @param targetOutputFileBytes target output file size
 * @param grandparentOverlapLimit output split overlap limit
 * @param estimatedInputBytes total selected input bytes
 * @param reason selection trigger
 * @param score source-level score
 * @param pointerUpdate next compaction pointer
 */
public record CompactionPlan(
        int inputLevel,
        int outputLevel,
        List<CompactionFile> primaryInputs,
        List<CompactionFile> outputLevelInputs,
        List<CompactionFile> grandparents,
        byte[] smallestUserKey,
        byte[] largestUserKey,
        long oldestSnapshotSequence,
        long targetOutputFileBytes,
        long grandparentOverlapLimit,
        long estimatedInputBytes,
        CompactionReason reason,
        double score,
        byte[] pointerUpdate) {
    /** Copies collections and byte arrays and validates adjacent levels. */
    public CompactionPlan {
        primaryInputs = List.copyOf(primaryInputs);
        outputLevelInputs = List.copyOf(outputLevelInputs);
        grandparents = List.copyOf(grandparents);
        smallestUserKey = smallestUserKey.clone();
        largestUserKey = largestUserKey.clone();
        pointerUpdate = pointerUpdate.clone();
        if (primaryInputs.isEmpty() || outputLevel != inputLevel + 1 || oldestSnapshotSequence < 0)
            throw new IllegalArgumentException("invalid compaction plan");
    }

    /**
     * Returns the lower bound.
     *
     * @return defensive key copy
     */
    @Override
    public byte[] smallestUserKey() {
        return smallestUserKey.clone();
    }

    /**
     * Returns the upper bound.
     *
     * @return defensive key copy
     */
    @Override
    public byte[] largestUserKey() {
        return largestUserKey.clone();
    }

    /**
     * Returns the next picker pointer.
     *
     * @return defensive key copy
     */
    @Override
    public byte[] pointerUpdate() {
        return pointerUpdate.clone();
    }
}
