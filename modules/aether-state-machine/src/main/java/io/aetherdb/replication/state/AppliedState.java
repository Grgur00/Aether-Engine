package io.aetherdb.replication.state;

/**
 * Atomically published deterministic state-machine progress.
 *
 * @param index last applied replicated-log index
 * @param term term associated with {@code index}
 * @param stateSequence last applied logical mutation sequence
 * @param entryHash SHA-256 hash of the applied log entry
 * @param appliedHash SHA-256 hash of the resulting applied state
 */
public record AppliedState(
        long index, long term, long stateSequence, byte[] entryHash, byte[] appliedHash) {
    /** Validates monotonic coordinates and defensively copies both hashes. */
    public AppliedState {
        if (index < 0
                || term < 0
                || stateSequence < 0
                || entryHash == null
                || entryHash.length != 32
                || appliedHash == null
                || appliedHash.length != 32)
            throw new IllegalArgumentException("invalid applied state");
        entryHash = entryHash.clone();
        appliedHash = appliedHash.clone();
    }

    /**
     * Returns the hash of the applied log entry.
     *
     * @return a defensive copy of the entry hash
     */
    @Override
    public byte[] entryHash() {
        return entryHash.clone();
    }

    /**
     * Returns the hash of the resulting state.
     *
     * @return a defensive copy of the applied-state hash
     */
    @Override
    public byte[] appliedHash() {
        return appliedHash.clone();
    }
}
