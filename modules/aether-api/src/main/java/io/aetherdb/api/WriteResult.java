package io.aetherdb.api;

/**
 * Successful synchronous write metadata.
 * @param operationCount number of applied mutations
 * @param firstSequence first allocated sequence, or zero for an empty write
 * @param lastSequence last allocated sequence, or zero for an empty write
 * @param requestedDurability requested durability mode
 * @param durabilityBarrierPerformed whether the engine performed a storage barrier
 */
public record WriteResult(int operationCount, long firstSequence, long lastSequence,
        DurabilityMode requestedDurability, boolean durabilityBarrierPerformed) {
    /** Validates operation and sequence-range consistency. */
    public WriteResult {
        if (operationCount < 0 || firstSequence < 0 || lastSequence < 0 || requestedDurability == null)
            throw new IllegalArgumentException("invalid write result");
        if (operationCount == 0 && (firstSequence != 0 || lastSequence != 0))
            throw new IllegalArgumentException("empty write has no sequence range");
        if (operationCount > 0 && (firstSequence <= 0 || lastSequence < firstSequence))
            throw new IllegalArgumentException("nonempty write requires a sequence range");
    }
    /** Reports whether the write allocated sequences.
     * @return {@code true} for a non-empty write */
    public boolean hasSequenceRange() { return operationCount > 0; }
}
