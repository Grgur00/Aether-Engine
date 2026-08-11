package io.aetherdb.codec.generated;

import io.aetherdb.api.typed.ValueCodec;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Bounded canonical writer used by generated codecs, never application code. */
public final class CanonicalRecordWriter {
    /** Fixed AER1 record-header length. */
    public static final int HEADER_BYTES = 24;

    private static final int CANONICAL_ORDER = 0x0000_0002;
    private final int maximumBytes;
    private final List<byte[]> entries = new ArrayList<>();
    private int previousFieldId;
    private int totalBytes = HEADER_BYTES;

    /**
     * Creates a writer with a strict encoded-record bound.
     *
     * @param maximumBytes maximum completed record length
     */
    public CanonicalRecordWriter(int maximumBytes) {
        if (maximumBytes < HEADER_BYTES) throw new IllegalArgumentException("invalid record bound");
        this.maximumBytes = maximumBytes;
    }

    /**
     * Appends one field in strictly increasing ID order.
     *
     * @param fieldId positive stable field ID
     * @param wireType AER1 wire-type code
     * @param payload canonical field payload
     */
    public void field(int fieldId, int wireType, byte[] payload) {
        if (fieldId <= previousFieldId)
            throw new IllegalArgumentException("fields are not canonical");
        if (payload == null) throw new IllegalArgumentException("null payload");
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        writeUnsignedVarint(entry, fieldId);
        entry.write(wireType);
        entry.write(0);
        writeUnsignedVarint(entry, payload.length);
        entry.writeBytes(payload);
        byte[] encoded = entry.toByteArray();
        totalBytes = Math.addExact(totalBytes, encoded.length);
        if (totalBytes > maximumBytes) throw new IllegalArgumentException("RECORD_LENGTH_EXCEEDED");
        entries.add(encoded);
        previousFieldId = fieldId;
    }

    /**
     * Completes the record header and checksum.
     *
     * @return canonical AER1 payload
     */
    public byte[] finish() {
        ByteBuffer output = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        output.put(new byte[] {'A', 'E', 'R', '1'});
        output.putShort((short) 1).putShort((short) HEADER_BYTES);
        output.putInt(entries.size()).putInt(totalBytes).putInt(CANONICAL_ORDER).putInt(0);
        for (byte[] entry : entries) output.put(entry);
        byte[] bytes = output.array();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(20, maskedCrc32c(bytes));
        return bytes;
    }

    /**
     * Encodes a boolean payload.
     *
     * @param value logical value
     * @return one canonical byte
     */
    public static byte[] bool(boolean value) {
        return new byte[] {(byte) (value ? 1 : 0)};
    }

    /**
     * Encodes a zigzag signed integer.
     *
     * @param value logical value
     * @return canonical varint
     */
    public static byte[] signedLong(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(10);
        writeUnsignedVarint(output, (value << 1) ^ (value >> 63));
        return output.toByteArray();
    }

    /** Encodes an exact signed byte. */
    public static byte[] signedByte(byte value) {
        return new byte[] {value};
    }

    /** Encodes an unsigned Java {@code char} code unit. */
    public static byte[] character(char value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(3);
        writeUnsignedVarint(output, value);
        return output.toByteArray();
    }

    /** Encodes a canonical IEEE-754 single-precision value. */
    public static byte[] fixed32(float value) {
        int bits = Float.floatToIntBits(value);
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bits).array();
    }

    /**
     * Encodes a canonical fixed-width double.
     *
     * @param value logical value
     * @return eight bytes
     */
    public static byte[] fixed64(double value) {
        long bits = Double.doubleToLongBits(value);
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array();
    }

    /**
     * Encodes and bounds a UTF-8 string.
     *
     * @param value logical string
     * @param maximumBytes field-specific byte bound
     * @return UTF-8 bytes
     */
    public static byte[] string(String value, int maximumBytes) {
        if (value == null) throw new IllegalArgumentException("null string");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes)
            throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        return bytes;
    }

    /**
     * Encodes a UUID in network byte order.
     *
     * @param value UUID
     * @return sixteen bytes
     */
    public static byte[] uuid(UUID value) {
        if (value == null) throw new IllegalArgumentException("null UUID");
        return ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    /**
     * Encodes epoch seconds and nanoseconds canonically.
     *
     * @param value instant
     * @return temporal payload
     */
    public static byte[] instant(Instant value) {
        if (value == null) throw new IllegalArgumentException("null Instant");
        ByteArrayOutputStream output = new ByteArrayOutputStream(15);
        writeUnsignedVarint(output, (value.getEpochSecond() << 1) ^ (value.getEpochSecond() >> 63));
        writeUnsignedVarint(output, value.getNano());
        return output.toByteArray();
    }

    /** Encodes a local date as a zigzag epoch-day value. */
    public static byte[] localDate(LocalDate value) {
        if (value == null) throw new IllegalArgumentException("null LocalDate");
        return signedLong(value.toEpochDay());
    }

    /** Encodes a local time as an unsigned nanosecond-of-day value. */
    public static byte[] localTime(LocalTime value) {
        if (value == null) throw new IllegalArgumentException("null LocalTime");
        ByteArrayOutputStream output = new ByteArrayOutputStream(10);
        writeUnsignedVarint(output, value.toNanoOfDay());
        return output.toByteArray();
    }

    /** Encodes a local date-time as independently canonical date and time components. */
    public static byte[] localDateTime(LocalDateTime value) {
        if (value == null) throw new IllegalArgumentException("null LocalDateTime");
        ByteArrayOutputStream output = new ByteArrayOutputStream(20);
        output.writeBytes(signedLong(value.toLocalDate().toEpochDay()));
        writeUnsignedVarint(output, value.toLocalTime().toNanoOfDay());
        return output.toByteArray();
    }

    /** Encodes a duration as zigzag seconds followed by canonical nanos. */
    public static byte[] duration(Duration value) {
        if (value == null) throw new IllegalArgumentException("null Duration");
        ByteArrayOutputStream output = new ByteArrayOutputStream(15);
        output.writeBytes(signedLong(value.getSeconds()));
        writeUnsignedVarint(output, value.getNano());
        return output.toByteArray();
    }

    /** Encodes a minimally represented two's-complement integer under a byte bound. */
    public static byte[] bigInteger(BigInteger value, int maximumBytes) {
        if (value == null) throw new IllegalArgumentException("null BigInteger");
        byte[] encoded = value.toByteArray();
        if (encoded.length > maximumBytes)
            throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        return encoded;
    }

    /** Encodes a decimal scale followed by its canonical unscaled integer. */
    public static byte[] bigDecimal(BigDecimal value, int maximumBytes) {
        if (value == null) throw new IllegalArgumentException("null BigDecimal");
        byte[] integer = value.unscaledValue().toByteArray();
        ByteArrayOutputStream output = new ByteArrayOutputStream(integer.length + 5);
        output.writeBytes(signedLong(value.scale()));
        output.writeBytes(integer);
        byte[] encoded = output.toByteArray();
        if (encoded.length > maximumBytes)
            throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        return encoded;
    }

    /** Copies a bounded byte-array field. */
    public static byte[] bytes(byte[] value, int maximumBytes) {
        if (value == null) throw new IllegalArgumentException("null byte array");
        if (value.length > maximumBytes)
            throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        return java.util.Arrays.copyOf(value, value.length);
    }

    /** Encodes a nested record with explicit schema UUID, writer version, and payload length. */
    public static <T> byte[] nested(ValueCodec<T> codec, T value, int maximumBytes) {
        if (codec == null || value == null)
            throw new IllegalArgumentException("null nested codec or value");
        byte[] payload = codec.encode(value);
        int total = Math.addExact(24, payload.length);
        if (total > maximumBytes || payload.length > codec.maximumEncodedSize(value)) {
            throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        }
        return ByteBuffer.allocate(total)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(codec.schemaId().getMostSignificantBits())
                .putLong(codec.schemaId().getLeastSignificantBits())
                .putInt(codec.currentSchemaVersion())
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    static int maskedCrc32c(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, 20);
        crc.update(new byte[4], 0, 4);
        crc.update(bytes, 24, bytes.length - 24);
        int value = (int) crc.getValue();
        return Integer.rotateRight(value, 15) + 0xa282ead8;
    }

    static void writeUnsignedVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7fL) != 0) {
            output.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.write((int) value);
    }
}
