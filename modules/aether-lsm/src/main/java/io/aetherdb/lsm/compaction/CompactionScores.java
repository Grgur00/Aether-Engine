package io.aetherdb.lsm.compaction;

/** Scores for L0 through L5; L6 never compacts downward. */
public record CompactionScores(double[] values) {
    public CompactionScores { if (values == null || values.length != 6) throw new IllegalArgumentException("six scores required"); values = values.clone(); }
    @Override public double[] values() { return values.clone(); }
    public double score(int level) { return values[level]; }
}
