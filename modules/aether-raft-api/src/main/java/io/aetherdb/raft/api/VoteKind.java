package io.aetherdb.raft.api;

public enum VoteKind { PRE_VOTE(1), REQUEST_VOTE(2); public final int code; VoteKind(int code) { this.code = code; } }
