package io.aetherdb.lsm.read;

import java.util.List;

/**
 * Immutable source inventory used to construct a published read view.
 *
 * @param activeMemTable current mutable MemTable, if present
 * @param immutableMemTables immutable MemTables ordered newest first
 * @param version current retained SSTable version, if present
 * @param visibleSequence maximum sequence visible to unsnapshotted reads
 */
public record ReadTopology(
        RetainedSource activeMemTable,
        List<? extends RetainedSource> immutableMemTables,
        RetainedSource version,
        long visibleSequence) {
    /** Copies the immutable-source list and validates visibility. */
    public ReadTopology {
        immutableMemTables = List.copyOf(immutableMemTables);
        if (visibleSequence < 0)
            throw new IllegalArgumentException("visibleSequence must be non-negative");
    }
}
