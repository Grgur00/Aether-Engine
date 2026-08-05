package io.aetherdb.codec.generated;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Bounded canonical writer used by generated codecs, never application code. */
public final class CanonicalRecordWriter {
    public static final int HEADER_BYTES = 24;
    private static final int CANONICAL_ORDER = 0x0000_0002;
    private final int maximumBytes;
    private final List<byte[]> entries = new ArrayList<>();
    private int previousFieldId;
    private int totalBytes = HEADER_BYTES;

    public CanonicalRecordWriter(int maximumBytes) {
        if (maximumBytes < HEADER_BYTES) throw new IllegalArgumentException("invalid record bound");
        this.maximumBytes = maximumBytes;
    }

    public void field(int fieldId, int wireType, byte[] payload) {
        if (fieldId <= previousFieldId) throw new IllegalArgumentException("fields are not canonical");
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

    public static byte[] bool(boolean value) { return new byte[] {(byte) (value ? 1 : 0)}; }

    public static byte[] signedLong(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(10);
        writeUnsignedVarint(output, (value << 1) ^ (value >> 63));
        return output.toByteArray();
    }

    public static byte[] fixed64(double value) {
        long bits = Double.doubleToLongBits(value);
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array();
    }

    public static byte[] string(String value, int maximumBytes) {
        if (value == null) throw new IllegalArgumentException("null string");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("FIELD_LENGTH_EXCEEDED");
        return bytes;
    }

    public static byte[] uuid(UUID value) {
        if (value == null) throw new IllegalArgumentException("null UUID");
        return ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static byte[] instant(Instant value) {
        if (value == null) throw new IllegalArgumentException("null Instant");
        ByteArrayOutputStream output = new ByteArrayOutputStream(15);
        writeUnsignedVarint(output, (value.getEpochSecond() << 1) ^ (value.getEpochSecond() >> 63));
        writeUnsignedVarint(output, value.getNano());
        return output.toByteArray();
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
