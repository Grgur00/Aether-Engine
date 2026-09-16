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
     * @param result privately owned immutable user value
     */
    record Found(long sequence, io.aetherdb.api.result.LookupResult result) implements SSTableLookup {
        /** Validates sequence and immutable result ownership. */
        public Found {
            if (sequence <= 0 || result == null || !result.isFound())
                throw new IllegalArgumentException("invalid found result");
        }

        public Found(long sequence, byte[] value) {
            this(sequence, io.aetherdb.api.result.LookupResult.found(value));
        }

        /**
         * Returns the visible value.
         *
         * @return defensive value copy
         */
        public byte[] value() {
            return result.value();
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
