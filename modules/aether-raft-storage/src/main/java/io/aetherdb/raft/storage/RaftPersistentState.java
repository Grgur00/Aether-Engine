package io.aetherdb.raft.storage;

import java.util.Optional;
import java.util.UUID;

public record RaftPersistentState(long generation, long currentTerm, Optional<UUID> votedFor) {
    public RaftPersistentState { if(generation<=0||currentTerm<0) throw new IllegalArgumentException("invalid persistent state"); votedFor=Optional.ofNullable(votedFor).orElse(Optional.empty()); if(currentTerm==0&&votedFor.isPresent()) throw new IllegalArgumentException("term zero cannot have a vote"); }
}
