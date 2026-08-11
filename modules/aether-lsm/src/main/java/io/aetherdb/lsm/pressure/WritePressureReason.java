package io.aetherdb.lsm.pressure;

/** Observable condition contributing to write pressure. */
public enum WritePressureReason {
    /** Too many immutable MemTables await flush. */
    IMMUTABLE_MEMTABLES,
    /** Native-memory allocation capacity is unavailable. */
    NATIVE_CAPACITY,
    /** Retained WAL bytes exceed policy thresholds. */
    WAL_BYTES,
    /** Level-zero file count exceeds policy thresholds. */
    LEVEL_ZERO_FILES,
    /** Estimated compaction debt exceeds policy thresholds. */
    COMPACTION_DEBT,
    /** Usable disk space is below a safety threshold. */
    DISK_SPACE,
    /** A required background worker failed. */
    BACKGROUND_FAILURE,
    /** An administrator paused write admission. */
    ADMINISTRATIVE_PAUSE
}
