package io.aetherdb.codec;

import io.aetherdb.api.typed.ValueCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Versioned envelope associating an encoded value with its durable schema identity. */
public final class TypedValueEnvelope {
    /** Fixed envelope header length. */
    public static final int HEADER_BYTES = 40;

    private TypedValueEnvelope() {}

    /** Encodes a non-null logical value with schema metadata.
     * @param c value codec
     * @param value logical value
     * @param <V> value type
     * @return physical value bytes */
    public static <V> byte[] encode(ValueCodec<V> c, V value) {
        if (value == null) throw new IllegalArgumentException("null values are forbidden");
        byte[] payload = c.encode(value);
        if (payload == null || payload.length > c.maximumEncodedSize(value)) {
            throw new IllegalArgumentException("encoded value exceeds codec bound");
        }
        return ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN)
                .put("AETV".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1)
                .putShort((short) HEADER_BYTES).putLong(c.schemaId().getMostSignificantBits())
                .putLong(c.schemaId().getLeastSignificantBits()).putInt(c.currentSchemaVersion())
                .putInt(0).putInt(payload.length).putInt(0).put(payload).array();
    }

    /** Validates an envelope and decodes its versioned payload.
     * @param c value codec
     * @param in physical value bytes
     * @param <V> value type
     * @return decoded logical value */
    public static <V> V decode(ValueCodec<V> c, byte[] in) {
        if (in == null || in.length < HEADER_BYTES) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4]; b.get(magic);
        if (!Arrays.equals(magic, "AETV".getBytes(StandardCharsets.US_ASCII)) || b.getShort() != 1
                || b.getShort() != HEADER_BYTES || b.getLong() != c.schemaId().getMostSignificantBits()
                || b.getLong() != c.schemaId().getLeastSignificantBits()) throw invalid();
        int version = b.getInt(), flags = b.getInt(), length = b.getInt(), reserved = b.getInt();
        if (flags != 0 || reserved != 0 || length != b.remaining()) throw invalid();
        byte[] payload = new byte[length]; b.get(payload);
        return c.decode(version, payload);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid typed value envelope");
    }
}
