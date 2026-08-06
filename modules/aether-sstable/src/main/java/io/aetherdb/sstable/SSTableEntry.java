package io.aetherdb.sstable;

/**
 * Immutable decoded internal table entry.
 *
 * @param key internal key
 * @param value raw value bytes, empty for tombstones
 */
public record SSTableEntry(InternalKey key, byte[] value) {
    /** Validates the entry and copies its value bytes. */
    public SSTableEntry {
        if (key == null || value == null || (key.type() == 2 && value.length != 0)) {
            throw new IllegalArgumentException("invalid SSTable entry");
        }
        value = value.clone();
    }
    /**
     * Returns the raw value.
     *
     * @return defensive value copy
     */
    @Override public byte[] value() { return value.clone(); }
}
