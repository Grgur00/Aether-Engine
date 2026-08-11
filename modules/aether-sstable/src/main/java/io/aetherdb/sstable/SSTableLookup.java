package io.aetherdb.sstable;

/** Point-lookup outcome preserving the distinction between absence and tombstones. */
public sealed interface SSTableLookup
        permits SSTableLookup.Found, SSTableLookup.Tombstone, SSTableLookup.Absent {
    /**
     * Returns the selected visibility sequence.
     *
     * @return visible sequence, or zero when no candidate exists
     */
    long sequence();

    /**
     * Visible value result.
     *
     * @param sequence selected sequence
     * @param value raw user value
     */
    record Found(long sequence, byte[] value) implements SSTableLookup {
        /** Validates sequence and copies value bytes. */
        public Found {
            if (sequence <= 0 || value == null)
                throw new IllegalArgumentException("invalid found result");
            value = value.clone();
        }

        /**
         * Returns the visible value.
         *
         * @return defensive value copy
         */
        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    /**
     * Visible deletion marker.
     *
     * @param sequence selected deletion sequence
     */
    record Tombstone(long sequence) implements SSTableLookup {
        /** Validates the visible sequence. */
        public Tombstone {
            if (sequence <= 0) throw new IllegalArgumentException("invalid tombstone result");
        }
    }

    /** No visible version in this table. */
    record Absent() implements SSTableLookup {
        @Override
        public long sequence() {
            return 0;
        }
    }
}
