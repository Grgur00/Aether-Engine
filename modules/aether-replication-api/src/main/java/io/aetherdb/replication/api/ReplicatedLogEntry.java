package io.aetherdb.replication.api;

import java.util.Arrays;
import java.util.UUID;

/** Immutable decoded replicated-log entry metadata and exact payload. */
public record ReplicatedLogEntry(ReplicatedEntryType type, int payloadFormatVersion, long index,
                                 long term, UUID commandId, long sequenceStart,
                                 long stateSequenceAfter, byte[] previousEntryHash,
                                 byte[] entryHash, byte[] payload) {
    /** Validates semantic coordinates, entry kind, and defensively copies all byte arrays. */
    public ReplicatedLogEntry {
        UUID zero = new UUID(0, 0);
        if (type == null || payloadFormatVersion < 0 || index < 1 || term < 1 || commandId == null
                || sequenceStart < 0 || stateSequenceAfter < 0 || previousEntryHash == null
                || previousEntryHash.length != 32 || entryHash == null || entryHash.length != 32
                || payload == null || payload.length > 67_108_664
                || type == ReplicatedEntryType.COMMAND && (commandId.equals(zero)
                        || payloadFormatVersion != 1 || sequenceStart < 1
                        || stateSequenceAfter < sequenceStart || payload.length == 0)
                || type != ReplicatedEntryType.COMMAND && !commandId.equals(zero)
                || (type == ReplicatedEntryType.NOOP || type == ReplicatedEntryType.BARRIER)
                        && (payload.length != 0 || payloadFormatVersion != 0 || sequenceStart != 0)) {
            throw new IllegalArgumentException("invalid replicated log entry");
        }
        previousEntryHash = Arrays.copyOf(previousEntryHash, 32);
        entryHash = Arrays.copyOf(entryHash, 32); payload = Arrays.copyOf(payload, payload.length);
    }
    /** Returns the previous hash defensively. */ @Override public byte[] previousEntryHash() { return previousEntryHash.clone(); }
    /** Returns this entry hash defensively. */ @Override public byte[] entryHash() { return entryHash.clone(); }
    /** Returns exact payload bytes defensively. */ @Override public byte[] payload() { return payload.clone(); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof ReplicatedLogEntry entry && type == entry.type
                && payloadFormatVersion == entry.payloadFormatVersion && index == entry.index && term == entry.term
                && commandId.equals(entry.commandId) && sequenceStart == entry.sequenceStart
                && stateSequenceAfter == entry.stateSequenceAfter
                && Arrays.equals(previousEntryHash, entry.previousEntryHash)
                && Arrays.equals(entryHash, entry.entryHash) && Arrays.equals(payload, entry.payload);
    }
    @Override public int hashCode() {
        int result = java.util.Objects.hash(type, payloadFormatVersion, index, term, commandId,
                sequenceStart, stateSequenceAfter);
        result = 31 * result + Arrays.hashCode(previousEntryHash);
        result = 31 * result + Arrays.hashCode(entryHash); return 31 * result + Arrays.hashCode(payload);
    }
}
