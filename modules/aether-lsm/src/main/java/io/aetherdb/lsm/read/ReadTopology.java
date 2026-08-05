package io.aetherdb.lsm.read;

import java.util.List;

/** Immutable source inventory used to construct a published read view. */
public record ReadTopology(
        RetainedSource activeMemTable,
        List<? extends RetainedSource> immutableMemTables,
        RetainedSource version,
        long visibleSequence) {
    public ReadTopology {
        immutableMemTables = List.copyOf(immutableMemTables);
        if (visibleSequence < 0) throw new IllegalArgumentException("visibleSequence must be non-negative");
    }
}
