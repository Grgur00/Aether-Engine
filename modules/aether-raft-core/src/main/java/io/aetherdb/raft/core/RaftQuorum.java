package io.aetherdb.raft.core;

/** Strict-majority arithmetic shared by Raft election and commit logic. */
public final class RaftQuorum {
    private RaftQuorum() {}

    /**
     * Computes the number of voters required for a strict majority.
     *
     * @param voters positive voter count
     * @return required acknowledgements
     */
    public static int required(int voters) {
        if (voters <= 0) throw new IllegalArgumentException("voters must be positive");
        return voters / 2 + 1;
    }
}
