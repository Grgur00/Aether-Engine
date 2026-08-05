package io.aetherdb.lsm.compaction;

/** Scores for L0 through L5; L6 never compacts downward.
 * @param values six level scores */
public record CompactionScores(double[] values) {
    /** Validates and copies the six scores. */
    public CompactionScores { if (values == null || values.length != 6) throw new IllegalArgumentException("six scores required"); values = values.clone(); }
    /** Returns all scores.
     * @return defensive score copy */
    @Override public double[] values() { return values.clone(); }
    /** Returns one level score.
     * @param level level from zero through five
     * @return score */
    public double score(int level) { return values[level]; }
}
