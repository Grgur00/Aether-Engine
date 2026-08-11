package io.aetherdb.raft.storage;

import java.util.Optional;
import java.util.UUID;

/**
 * Minimum durable election state that must survive a node restart.
 *
 * @param generation monotonic slot generation
 * @param currentTerm latest observed election term
 * @param votedFor candidate voted for in the current term, if any
 */
public record RaftPersistentState(long generation, long currentTerm, Optional<UUID> votedFor) {
    /** Validates term/vote consistency and normalizes a null optional. */
    public RaftPersistentState {
        if (generation <= 0 || currentTerm < 0)
            throw new IllegalArgumentException("invalid persistent state");
        votedFor = Optional.ofNullable(votedFor).orElse(Optional.empty());
        if (currentTerm == 0 && votedFor.isPresent())
            throw new IllegalArgumentException("term zero cannot have a vote");
    }
}
