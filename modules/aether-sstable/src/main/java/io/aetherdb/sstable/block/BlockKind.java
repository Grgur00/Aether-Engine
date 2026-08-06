package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;

/** Persistent SSTable block-kind identifiers. */
public enum BlockKind {
    /** Sorted internal-key/value records. */ DATA(1),
    /** Data-block upper-bound keys and handles. */ INDEX(2),
    /** Full-file user-key Bloom filter. */ FILTER(3),
    /** Required immutable table properties. */ PROPERTIES(4),
    /** Named metadata block handles. */ METAINDEX(5);

    private final int code;
    BlockKind(int code) { this.code = code; }

    /**
     * Returns the persistent identifier.
     *
     * @return persistent one-byte identifier
     */
    public int code() { return code; }

    /**
     * Resolves a persistent identifier.
     *
     * @param code unsigned identifier
     * @return corresponding kind
     */
    public static BlockKind fromCode(int code) {
        for (BlockKind kind : values()) if (kind.code == code) return kind;
        throw new SSTableCorruptionException("unknown block kind: " + code);
    }
}
