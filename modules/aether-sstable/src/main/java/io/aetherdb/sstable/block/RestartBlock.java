package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Restart-prefix-compressed sorted key/value block. */
public final class RestartBlock {
    private RestartBlock() {}

    /**
     * Encodes strictly sorted entries using restart-prefix compression.
     *
     * @param entries sorted key/value entries
     * @param restartInterval positive entries per restart point
     * @return encoded block bytes
     */
    public static byte[] encode(List<Entry> entries, int restartInterval) {
        return encode(entries, restartInterval, Arrays::compareUnsigned);
    }

    /**
     * Encodes entries using a context-specific strict key comparator.
     *
     * @param entries sorted key/value entries
     * @param restartInterval positive entries per restart point
     * @param comparator key ordering used by the enclosing block kind
     * @return encoded block bytes
     */
    public static byte[] encode(
            List<Entry> entries, int restartInterval, Comparator<byte[]> comparator) {
        Encoder encoder = new Encoder(restartInterval, comparator);
        for (Entry entry : entries) encoder.add(entry.key, entry.value);
        return encoder.finish();
    }

    /** Returns the exact encoded body contribution for one entry. */
    public static int encodedEntrySize(byte[] previous, Entry entry, boolean restart) {
        return encodedEntrySize(previous, entry.key, entry.value.length, restart);
    }

    /** Returns the exact encoded body contribution for raw key/value bytes. */
    public static int encodedEntrySize(
            byte[] previous, byte[] key, int valueLength, boolean restart) {
        int shared = restart ? 0 : shared(previous, key);
        int suffix = key.length - shared;
        return Math.addExact(
                Math.addExact(Varint32.encodedLength(shared), Varint32.encodedLength(suffix)),
                Math.addExact(
                        Varint32.encodedLength(valueLength), Math.addExact(suffix, valueLength)));
    }

    /** Incremental restart-block encoder without per-entry adapter allocations. */
    public static final class Encoder {
        private final int restartInterval;
        private final Comparator<byte[]> comparator;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final List<Integer> restarts = new ArrayList<>();
        private byte[] previous = new byte[0];
        private int entryCount;
        private boolean finished;

        /** Creates an encoder using the supplied restart interval and ordering. */
        public Encoder(int restartInterval, Comparator<byte[]> comparator) {
            if (restartInterval <= 0)
                throw new IllegalArgumentException("restart interval must be positive");
            this.restartInterval = restartInterval;
            this.comparator = java.util.Objects.requireNonNull(comparator, "comparator");
        }

        /** Adds one strictly ordered entry without copying its arrays. */
        public void add(byte[] key, byte[] value) {
            if (finished) throw new IllegalStateException("encoder is finished");
            if (entryCount > 0 && comparator.compare(previous, key) >= 0)
                throw new IllegalArgumentException("keys not strictly ordered");
            boolean restart = entryCount % restartInterval == 0;
            int shared = restart ? 0 : shared(previous, key);
            if (restart) restarts.add(body.size());
            Varint32.write(body, shared);
            Varint32.write(body, key.length - shared);
            Varint32.write(body, value.length);
            body.write(key, shared, key.length - shared);
            body.writeBytes(value);
            previous = key;
            entryCount++;
        }

        /** Finishes the restart suffix and returns the canonical block. */
        public byte[] finish() {
            if (finished) throw new IllegalStateException("encoder is finished");
            finished = true;
            if (entryCount == 0) restarts.add(0);
            ByteBuffer suffix =
                    ByteBuffer.allocate(restarts.size() * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset : restarts) suffix.putInt(offset);
            suffix.putInt(restarts.size());
            body.writeBytes(suffix.array());
            return body.toByteArray();
        }
    }

    /**
     * Strictly decodes a restart-prefix-compressed block.
     *
     * @param raw encoded block bytes
     * @return decoded sorted entries
     */
    public static List<Entry> decode(byte[] raw) {
        return decode(raw, Arrays::compareUnsigned);
    }

    /**
     * Decodes and validates entries with a context-specific key comparator.
     *
     * @param raw encoded block bytes
     * @param comparator key ordering used by the enclosing block kind
     * @return decoded sorted entries
     */
    public static List<Entry> decode(byte[] raw, Comparator<byte[]> comparator) {
        if (comparator == null) throw new IllegalArgumentException("comparator is required");
        if (raw.length < 8) throw corrupt("block too short");
        ByteBuffer end = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int restartCount = end.getInt(raw.length - 4);
        if (restartCount <= 0 || restartCount > (raw.length - 4) / 4)
            throw corrupt("invalid restart count");
        int restartStart = raw.length - 4 - restartCount * 4;
        int previousRestart = -1;
        for (int i = 0; i < restartCount; i++) {
            int offset = end.getInt(restartStart + i * 4);
            if (offset < 0
                    || (restartStart == 0 ? offset != 0 : offset >= restartStart)
                    || offset <= previousRestart) throw corrupt("invalid restart offset");
            previousRestart = offset;
        }
        List<Entry> entries = new ArrayList<>();
        byte[] previous = new byte[0];
        int cursor = 0;
        while (cursor < restartStart) {
            Varint32.Decoded shared = Varint32.decode(raw, cursor, restartStart);
            cursor += shared.bytes();
            Varint32.Decoded suffix = Varint32.decode(raw, cursor, restartStart);
            cursor += suffix.bytes();
            Varint32.Decoded value = Varint32.decode(raw, cursor, restartStart);
            cursor += value.bytes();
            if (shared.value() > previous.length
                    || (long) cursor + suffix.value() + value.value() > restartStart)
                throw corrupt("entry exceeds block");
            byte[] key = Arrays.copyOf(previous, shared.value() + suffix.value());
            System.arraycopy(raw, cursor, key, shared.value(), suffix.value());
            cursor += suffix.value();
            byte[] bytes = Arrays.copyOfRange(raw, cursor, cursor + value.value());
            cursor += value.value();
            if (!entries.isEmpty() && comparator.compare(previous, key) >= 0)
                throw corrupt("unsorted block");
            entries.add(new Entry(key, bytes));
            previous = key;
        }
        if (cursor != restartStart) throw corrupt("block not exactly consumed");
        return entries;
    }

    private static int shared(byte[] left, byte[] right) {
        int limit = Math.min(left.length, right.length), i = 0;
        while (i < limit && left[i] == right[i]) i++;
        return i;
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }

    /**
     * Immutable decoded block entry.
     *
     * @param key copied key bytes
     * @param value copied value bytes
     */
    public record Entry(byte[] key, byte[] value) {
        /** Takes defensive copies of entry bytes. */
        public Entry {
            key = key.clone();
            value = value.clone();
        }

        /**
         * Returns the entry key.
         *
         * @return defensive key copy
         */
        @Override
        public byte[] key() {
            return key.clone();
        }

        /**
         * Returns the entry value.
         *
         * @return defensive value copy
         */
        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && Arrays.equals(key, entry.key)
                    && Arrays.equals(value, entry.value);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(key) + Arrays.hashCode(value);
        }
    }
}
