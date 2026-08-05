package io.aetherdb.raft.core;

public final class RaftQuorum {
    private RaftQuorum() {}
    public static int required(int voters) { if (voters <= 0) throw new IllegalArgumentException("voters must be positive"); return voters / 2 + 1; }
}
