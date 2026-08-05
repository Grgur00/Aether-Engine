package io.aetherdb.api;

/** Successful synchronous write metadata. */
public record WriteResult(int operationCount, long firstSequence, long lastSequence,
        DurabilityMode requestedDurability, boolean durabilityBarrierPerformed) {
    public WriteResult {
        if (operationCount < 0 || firstSequence < 0 || lastSequence < 0 || requestedDurability == null)
            throw new IllegalArgumentException("invalid write result");
        if (operationCount == 0 && (firstSequence != 0 || lastSequence != 0))
            throw new IllegalArgumentException("empty write has no sequence range");
        if (operationCount > 0 && (firstSequence <= 0 || lastSequence < firstSequence))
            throw new IllegalArgumentException("nonempty write requires a sequence range");
    }
    public boolean hasSequenceRange() { return operationCount > 0; }
}
