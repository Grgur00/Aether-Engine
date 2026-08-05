package io.aetherdb.codec.generated;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Strict canonical payload reader used by generated codecs. */
public final class CanonicalRecordReader {
    private final byte[] bytes;
    private final int fieldCount;
    private int offset = CanonicalRecordWriter.HEADER_BYTES;
    private int fieldsRead;
    private int previousFieldId;
    private int fieldId;
    private int wireType;
    private byte[] payload;

    public CanonicalRecordReader(byte[] bytes, int maximumBytes) {
        if (bytes == null || bytes.length < CanonicalRecordWriter.HEADER_BYTES
                || bytes.length > maximumBytes) throw invalid("RECORD_LENGTH_EXCEEDED");
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (header.get() != 'A' || header.get() != 'E' || header.get() != 'R'
                || header.get() != '1' || header.getShort() != 1
                || header.getShort() != CanonicalRecordWriter.HEADER_BYTES) {
            throw invalid("invalid AER1 header");
        }
        fieldCount = header.getInt();
        if (fieldCount < 0 || header.getInt() != bytes.length || header.getInt() != 2) {
            throw invalid("invalid AER1 metadata");
        }
        int expectedCrc = header.getInt();
        if (expectedCrc != CanonicalRecordWriter.maskedCrc32c(bytes)) {
            throw invalid("RECORD_CRC_MISMATCH");
        }
    }

    public boolean next() {
        if (fieldsRead == fieldCount) {
            if (offset != bytes.length) throw invalid("trailing record bytes");
            return false;
        }
        long decodedId = unsignedVarint();
        if (decodedId <= previousFieldId || decodedId > 536_870_911L) {
            throw invalid("FIELD_ORDER_INVALID");
        }
        fieldId = (int) decodedId;
        requireRemaining(2);
        wireType = bytes[offset++] & 0xff;
        int flags = bytes[offset++] & 0xff;
        if (flags != 0) throw invalid("unsupported field flags");
        long length = unsignedVarint();
        if (length > Integer.MAX_VALUE) throw invalid("FIELD_LENGTH_EXCEEDED");
        requireRemaining((int) length);
        payload = Arrays.copyOfRange(bytes, offset, offset + (int) length);
        offset += (int) length;
        previousFieldId = fieldId;
        fieldsRead++;
        return true;
    }

    public int fieldId() { return fieldId; }

    /** Returns the current field's stable AER1 wire-type identifier. */
    public int wireType() { return wireType; }

    /** Returns a defensive copy of the current field's canonical payload. */
    public byte[] rawPayload() { return Arrays.copyOf(payload, payload.length); }

    public void requireWireType(int expected) {
        if (wireType != expected) throw invalid("FIELD_WIRE_TYPE_MISMATCH at field " + fieldId);
    }

    public boolean boolValue() {
        if (payload.length != 1 || (payload[0] != 0 && payload[0] != 1)) {
            throw invalid("invalid boolean at field " + fieldId);
        }
        return payload[0] == 1;
    }

    public long signedLongValue() {
        long raw = payloadUnsignedVarint();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public double fixed64Value() {
        if (payload.length != 8) throw invalid("invalid fixed64 at field " + fieldId);
        return Double.longBitsToDouble(ByteBuffer.wrap(payload)
                .order(ByteOrder.LITTLE_ENDIAN).getLong());
    }

    public String stringValue(int maximumBytes) {
        if (payload.length > maximumBytes) throw invalid("FIELD_LENGTH_EXCEEDED");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        }
        catch (CharacterCodingException failure) {
            throw invalid("UTF8_INVALID");
        }
    }

    public UUID uuidValue() {
        if (payload.length != 16) throw invalid("invalid UUID at field " + fieldId);
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        return new UUID(input.getLong(), input.getLong());
    }

    public Instant instantValue() {
        int[] position = {0};
        long secondsRaw = payloadUnsignedVarint(position);
        long nanos = payloadUnsignedVarint(position);
        if (position[0] != payload.length || nanos > 999_999_999L) {
            throw invalid("invalid Instant at field " + fieldId);
        }
        long seconds = (secondsRaw >>> 1) ^ -(secondsRaw & 1);
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private long unsignedVarint() {
        int[] position = {offset};
        long value = unsignedVarint(bytes, position);
        offset = position[0];
        return value;
    }

    private long payloadUnsignedVarint() {
        int[] position = {0};
        long value = payloadUnsignedVarint(position);
        if (position[0] != payload.length) throw invalid("non-canonical varint");
        return value;
    }

    private long payloadUnsignedVarint(int[] position) {
        return unsignedVarint(payload, position);
    }

    private static long unsignedVarint(byte[] source, int[] position) {
        long value = 0;
        int shift = 0;
        while (position[0] < source.length && shift < 64) {
            int current = source[position[0]++] & 0xff;
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (shift > 0 && current == 0) throw invalid("non-canonical varint");
                return value;
            }
            shift += 7;
        }
        throw invalid("malformed varint");
    }

    private void requireRemaining(int length) {
        if (length < 0 || offset > bytes.length - length) throw invalid("truncated field");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
