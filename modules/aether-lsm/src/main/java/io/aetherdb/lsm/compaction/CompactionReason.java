package io.aetherdb.lsm.compaction;

/** Trigger that caused a compaction plan to be selected. */
public enum CompactionReason {
    /** A level reached its normal size or file-count score. */ SCORE,
    /** Level zero crossed its urgent backlog threshold. */ URGENT_L0,
    /** Level zero crossed the write-stop threshold. */ WRITE_STOP,
    /** An administrator explicitly requested compaction. */ MANUAL
}
