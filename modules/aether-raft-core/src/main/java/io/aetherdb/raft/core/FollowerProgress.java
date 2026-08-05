package io.aetherdb.raft.core;

public final class FollowerProgress {
    public enum Mode { PROBE, REPLICATE, SNAPSHOT_REQUIRED }
    private long nextIndex; private long matchIndex; private Mode mode = Mode.PROBE;
    public FollowerProgress(long leaderLastIndex) { nextIndex = Math.addExact(leaderLastIndex, 1); }
    public void matched(long durableIndex) { if (durableIndex < matchIndex) return; matchIndex = durableIndex; nextIndex = Math.addExact(durableIndex, 1); mode = Mode.REPLICATE; }
    public void rejected(long suggestedNextIndex) { nextIndex = Math.max(matchIndex + 1, suggestedNextIndex); mode = Mode.PROBE; }
    public void snapshotRequired() { mode = Mode.SNAPSHOT_REQUIRED; }
    public long nextIndex() { return nextIndex; } public long matchIndex() { return matchIndex; } public Mode mode() { return mode; }
}
