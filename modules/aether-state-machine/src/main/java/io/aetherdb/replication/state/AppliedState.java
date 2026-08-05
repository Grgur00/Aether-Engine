package io.aetherdb.replication.state;

/** Atomically published deterministic state-machine progress. */
public record AppliedState(long index, long term, long stateSequence, byte[] entryHash, byte[] appliedHash) {
    public AppliedState {
        if (index < 0 || term < 0 || stateSequence < 0 || entryHash == null || entryHash.length != 32 || appliedHash == null || appliedHash.length != 32)
            throw new IllegalArgumentException("invalid applied state");
        entryHash = entryHash.clone(); appliedHash = appliedHash.clone();
    }
    @Override public byte[] entryHash() { return entryHash.clone(); }
    @Override public byte[] appliedHash() { return appliedHash.clone(); }
}
