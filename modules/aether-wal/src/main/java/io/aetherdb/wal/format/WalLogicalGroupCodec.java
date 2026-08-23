package io.aetherdb.wal.format;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Encoder and strict reader for v1 logical mutation groups carried by WAL fragments. */
public final class WalLogicalGroupCodec {
    private static final byte PUT = 1;
    private static final byte DELETE = 2;
    private static final byte[] MAGIC = "AETHGRP1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_CRC_OFFSET = 44;

    private WalLogicalGroupCodec() {}

    /**
     * Encodes a write batch into canonical WAL logical-group bytes.
     *
     * @param batch ordered write batch
     * @param first first assigned sequence
     * @param last last assigned sequence
     * @return encoded group
     */
    public static byte[] encode(WriteBatch batch, long first, long last) {
        if (batch == null) throw new IllegalArgumentException("batch must not be null");
        int count = batch.operationCount();
        if (first < 1 || last < first || last - first + 1 != count)
            throw new IllegalArgumentException("invalid WAL logical-group sequence range");
        int size = WalFormatV1.GROUP_HEADER_BYTES;
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            byte[] key = mutation.key();
            int valueLength = mutation instanceof WriteBatch.Put put ? put.value().length : 0;
            size =
                    Math.addExact(
                            size,
                            Math.addExact(
                                    WalFormatV1.OPERATION_HEADER_BYTES,
                                    Math.addExact(key.length, valueLength)));
        }
        byte[] result = new byte[size];
        ByteBuffer bytes = little(result);
        bytes.put(MAGIC)
                .putShort((short) 1)
                .putShort((short) WalFormatV1.GROUP_HEADER_BYTES)
                .putInt(size)
                .putLong(first)
                .putLong(last)
                .putInt(count)
                .putInt(0)
                .putInt(0)
                .putInt(0);
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            byte[] key = mutation.key();
            byte[] value = mutation instanceof WriteBatch.Put put ? put.value() : new byte[0];
            bytes.put(mutation instanceof WriteBatch.Delete ? DELETE : PUT)
                    .put(new byte[3])
                    .putInt(key.length)
                    .putInt(value.length)
                    .put(key)
                    .put(value);
        }
        bytes.putInt(HEADER_CRC_OFFSET, MaskedCrc32c.masked(result, 0, HEADER_CRC_OFFSET));
        return result;
    }

    /**
     * Decodes and validates a WAL logical group.
     *
     * @param encoded exact logical group bytes
     * @return decoded immutable group
     */
    public static DecodedGroup decode(byte[] encoded) {
        if (encoded == null || encoded.length < WalFormatV1.GROUP_HEADER_BYTES)
            throw corrupt("short WAL logical group");
        ByteBuffer bytes = little(encoded);
        byte[] magic = new byte[MAGIC.length];
        bytes.get(magic);
        if (!Arrays.equals(magic, MAGIC)
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != WalFormatV1.GROUP_HEADER_BYTES
                || bytes.getInt() != encoded.length)
            throw corrupt("invalid WAL logical-group header");
        long first = bytes.getLong();
        long last = bytes.getLong();
        int count = bytes.getInt();
        if (bytes.getInt() != 0
                || bytes.getInt() != 0
                || bytes.getInt() != MaskedCrc32c.masked(encoded, 0, HEADER_CRC_OFFSET)
                || first < 1
                || last < first
                || last - first + 1 != count
                || count > WriteBatch.MAX_OPERATIONS)
            throw corrupt("invalid WAL logical-group metadata");
        List<Mutation> mutations = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (bytes.remaining() < WalFormatV1.OPERATION_HEADER_BYTES)
                throw corrupt("truncated WAL operation");
            int type = Byte.toUnsignedInt(bytes.get());
            if (bytes.get() != 0 || bytes.get() != 0 || bytes.get() != 0)
                throw corrupt("invalid WAL operation flags");
            int keyLength = bytes.getInt();
            int valueLength = bytes.getInt();
            if (keyLength < 0
                    || keyLength > WriteBatch.MAX_KEY_BYTES
                    || valueLength < 0
                    || valueLength > WriteBatch.MAX_VALUE_BYTES
                    || bytes.remaining() < (long) keyLength + valueLength
                    || type != PUT && type != DELETE
                    || type == DELETE && valueLength != 0)
                throw corrupt("invalid WAL operation");
            byte[] key = new byte[keyLength];
            byte[] value = new byte[valueLength];
            bytes.get(key).get(value);
            mutations.add(new Mutation(key, value, type == DELETE));
        }
        if (bytes.hasRemaining()) throw corrupt("WAL logical-group trailing bytes");
        return new DecodedGroup(first, last, mutations);
    }

    private static ByteBuffer little(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static WalCorruptionException corrupt(String message) {
        return new WalCorruptionException(message);
    }

    /** Decoded WAL logical group. */
    public record DecodedGroup(long firstSequence, long lastSequence, List<Mutation> mutations) {
        public DecodedGroup {
            if (firstSequence < 1
                    || lastSequence < firstSequence
                    || mutations == null
                    || lastSequence - firstSequence + 1 != mutations.size())
                throw new IllegalArgumentException("invalid decoded WAL logical group");
            mutations = List.copyOf(mutations);
        }
    }

    /** Decoded logical mutation. */
    public record Mutation(byte[] key, byte[] value, boolean delete) {
        public Mutation {
            if (key == null || value == null) throw new IllegalArgumentException("invalid mutation");
            key = key.clone();
            value = value.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }
}
