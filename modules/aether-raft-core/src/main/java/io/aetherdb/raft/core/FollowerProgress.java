package io.aetherdb.raft.core;

/** Leader-side replication progress for one follower. */
public final class FollowerProgress {
    /** Replication strategy selected for the follower. */
    public enum Mode {
        /** Send a bounded probe while searching for a matching prefix. */
        PROBE,
        /** Pipeline entries after establishing a matching prefix. */
        REPLICATE,
        /** Stop incremental replication and install a snapshot. */
        SNAPSHOT_REQUIRED
    }

    private long nextIndex;
    private long matchIndex;
    private Mode mode = Mode.PROBE;

    /**
     * Creates progress immediately after the leader's current log tip.
     *
     * @param leaderLastIndex leader's last log index
     */
    public FollowerProgress(long leaderLastIndex) {
        nextIndex = Math.addExact(leaderLastIndex, 1);
    }

    /**
     * Records a follower's durable successful match.
     *
     * @param durableIndex highest index durably stored by the follower
     */
    public void matched(long durableIndex) {
        if (durableIndex < matchIndex) return;
        matchIndex = durableIndex;
        nextIndex = Math.addExact(durableIndex, 1);
        mode = Mode.REPLICATE;
    }

    /**
     * Applies a rejection hint without regressing below the known match.
     *
     * @param suggestedNextIndex follower's next-index hint
     */
    public void rejected(long suggestedNextIndex) {
        nextIndex = Math.max(matchIndex + 1, suggestedNextIndex);
        mode = Mode.PROBE;
    }

    /** Marks the follower as requiring snapshot installation. */
    public void snapshotRequired() {
        mode = Mode.SNAPSHOT_REQUIRED;
    }

    /**
     * Returns the next index to send.
     *
     * @return next replication index
     */
    public long nextIndex() {
        return nextIndex;
    }

    /**
     * Returns confirmed durable progress.
     *
     * @return highest matched index
     */
    public long matchIndex() {
        return matchIndex;
    }

    /**
     * Returns the current strategy.
     *
     * @return replication mode
     */
    public Mode mode() {
        return mode;
    }
}
