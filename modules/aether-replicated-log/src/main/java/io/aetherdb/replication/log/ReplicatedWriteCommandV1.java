package io.aetherdb.replication.log;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.replication.api.StateSequenceRange;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Exact command-envelope and replicated write-batch body v1 codec. */
public record ReplicatedWriteCommandV1(UUID commandId, StateSequenceRange sequences, List<Operation> operations) {
    public static final int ENVELOPE_BYTES = 128;
    public static final int BATCH_HEADER_BYTES = 32;
    public static final int OPERATION_HEADER_BYTES = 16;
    public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    public ReplicatedWriteCommandV1 {
        if (isZero(commandId) || sequences == null || operations == null || operations.isEmpty() || operations.size() > 10_000
                || sequences.operationCount() != operations.size()) throw new IllegalArgumentException("invalid replicated write command");
        operations = List.copyOf(operations);
    }

    public static ReplicatedWriteCommandV1 fromBatch(UUID commandId, StateSequenceRange sequences, WriteBatch batch) {
        List<Operation> operations = new ArrayList<>(); int ordinal = 0;
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            if (mutation instanceof WriteBatch.Put put) operations.add(new Operation(Type.PUT, put.key(), put.value(), ordinal++));
            else if (mutation instanceof WriteBatch.Delete delete) operations.add(new Operation(Type.DELETE, delete.key(), new byte[0], ordinal++));
            else throw new IllegalArgumentException("unsupported mutation");
        }
        return new ReplicatedWriteCommandV1(commandId, sequences, operations);
    }

    public byte[] encode() {
        byte[] body = encodeBody(); byte[] result = new byte[ENVELOPE_BYTES + body.length]; byte[] bodyHash = sha256(body);
        ByteBuffer envelope = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        envelope.put("AECM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) ENVELOPE_BYTES);
        envelope.put((byte) 1).put((byte) 0).putShort((short) 0).putInt(1);
        putUuid(envelope, commandId); envelope.putLong(0).putLong(0).putLong(0);
        envelope.putLong(sequences.first()).putLong(sequences.last()).putInt(operations.size()).putInt(body.length).putInt(1).putInt(0);
        envelope.put(bodyHash); envelope.putInt(MaskedCrc32c.masked(result, 0, 120)).putInt(0);
        System.arraycopy(body, 0, result, ENVELOPE_BYTES, body.length); return result;
    }

    public static ReplicatedWriteCommandV1 decode(byte[] payload) {
        if (payload == null || payload.length < ENVELOPE_BYTES + BATCH_HEADER_BYTES || payload.length > ENVELOPE_BYTES + MAX_BODY_BYTES)
            throw new IllegalArgumentException("invalid replicated command length");
        ByteBuffer envelope = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[4]; envelope.get(magic);
        if (!Arrays.equals(magic, "AECM".getBytes(StandardCharsets.US_ASCII)) || envelope.getShort() != 1 || envelope.getShort() != 128
                || envelope.get() != 1 || envelope.get() != 0 || envelope.getShort() != 0 || envelope.getInt() != 1)
            throw new IllegalArgumentException("invalid command envelope");
        UUID commandId = getUuid(envelope);
        if (envelope.getLong() != 0 || envelope.getLong() != 0 || envelope.getLong() != 0) throw new IllegalArgumentException("unsupported client session fields");
        long first = envelope.getLong(), last = envelope.getLong(); int count = envelope.getInt(), bodyLength = envelope.getInt();
        if (envelope.getInt() != 1 || envelope.getInt() != 0 || bodyLength != payload.length - 128) throw new IllegalArgumentException("invalid command metadata");
        byte[] storedBodyHash = new byte[32]; envelope.get(storedBodyHash); int storedCrc = envelope.getInt();
        if (envelope.getInt() != 0 || storedCrc != MaskedCrc32c.masked(payload, 0, 120)) throw new IllegalArgumentException("command envelope integrity failure");
        byte[] body = Arrays.copyOfRange(payload, 128, payload.length);
        if (!MessageDigest.isEqual(storedBodyHash, sha256(body))) throw new IllegalArgumentException("command body hash mismatch");
        List<Operation> operations = decodeBody(body, count);
        return new ReplicatedWriteCommandV1(commandId, new StateSequenceRange(first, last), operations);
    }

    private byte[] encodeBody() {
        long length = BATCH_HEADER_BYTES, keyBytes = 0, valueBytes = 0;
        for (Operation operation : operations) { length = Math.addExact(length, OPERATION_HEADER_BYTES + operation.key.length + operation.value.length); keyBytes += operation.key.length; valueBytes += operation.value.length; }
        if (length > MAX_BODY_BYTES) throw new IllegalArgumentException("replicated command body exceeds 32 MiB");
        ByteBuffer body = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
        body.put("AEBT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 32).putInt((int) length).putInt(operations.size()).putLong(keyBytes).putLong(valueBytes);
        for (Operation operation : operations) {
            body.put((byte) operation.type.code).put((byte) 0).putShort((short) 0).putInt(operation.key.length).putInt(operation.value.length).putInt(operation.ordinal);
            body.put(operation.key).put(operation.value);
        }
        return body.array();
    }
    private static List<Operation> decodeBody(byte[] body, int expectedCount) {
        ByteBuffer bytes = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[4]; bytes.get(magic);
        if (!Arrays.equals(magic, "AEBT".getBytes(StandardCharsets.US_ASCII)) || bytes.getShort() != 1 || bytes.getShort() != 32
                || bytes.getInt() != body.length || bytes.getInt() != expectedCount) throw new IllegalArgumentException("invalid write-batch body header");
        long expectedKeys = bytes.getLong(), expectedValues = bytes.getLong(), actualKeys = 0, actualValues = 0;
        List<Operation> result = new ArrayList<>(expectedCount);
        for (int ordinal = 0; ordinal < expectedCount; ordinal++) {
            if (bytes.remaining() < 16) throw new IllegalArgumentException("truncated operation header");
            int typeCode = Byte.toUnsignedInt(bytes.get()); if (bytes.get() != 0 || bytes.getShort() != 0) throw new IllegalArgumentException("invalid operation flags");
            int keyLength = bytes.getInt(), valueLength = bytes.getInt(), storedOrdinal = bytes.getInt();
            Type type = Type.fromCode(typeCode);
            if (keyLength < 0 || keyLength > 65_536 || valueLength < 0 || valueLength > 16 * 1024 * 1024
                    || type == Type.DELETE && valueLength != 0 || storedOrdinal != ordinal || bytes.remaining() < keyLength + valueLength)
                throw new IllegalArgumentException("invalid replicated operation");
            byte[] key = new byte[keyLength], value = new byte[valueLength]; bytes.get(key).get(value);
            actualKeys += keyLength; actualValues += valueLength; result.add(new Operation(type, key, value, ordinal));
        }
        if (bytes.hasRemaining() || actualKeys != expectedKeys || actualValues != expectedValues) throw new IllegalArgumentException("write-batch totals or consumption mismatch");
        return result;
    }
    private static byte[] sha256(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static boolean isZero(UUID id) { return id == null || id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0; }
    private static void putUuid(ByteBuffer bytes, UUID id) { bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }

    public enum Type { PUT(1), DELETE(2); private final int code; Type(int code) { this.code = code; } static Type fromCode(int code) { if (code == 1) return PUT; if (code == 2) return DELETE; throw new IllegalArgumentException("unknown operation type"); } }
    public record Operation(Type type, byte[] key, byte[] value, int ordinal) {
        public Operation { if (type == null || key == null || value == null || ordinal < 0) throw new IllegalArgumentException("invalid operation"); key = key.clone(); value = value.clone(); }
        @Override public byte[] key() { return key.clone(); } @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Operation operation && type == operation.type && ordinal == operation.ordinal
                    && Arrays.equals(key, operation.key) && Arrays.equals(value, operation.value);
        }
        @Override public int hashCode() { return ((31 * type.hashCode() + Arrays.hashCode(key)) * 31 + Arrays.hashCode(value)) * 31 + ordinal; }
    }
}
