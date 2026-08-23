package io.aetherdb.reliability;

/** Deterministic byte-level corruption patterns for persisted-format campaigns. */
public enum CorruptionKind {
    /** Flip one bit at an exact byte offset. */
    FLIP_BIT,
    /** Truncate bytes after an exact prefix length. */
    TRUNCATE,
    /** Append deterministic nonzero garbage bytes. */
    APPEND_GARBAGE,
    /** Overwrite an exact byte range with one repeated byte. */
    OVERWRITE_RANGE,
    /** Keep a prefix that simulates a torn final write. */
    TORN_WRITE_PREFIX
}
