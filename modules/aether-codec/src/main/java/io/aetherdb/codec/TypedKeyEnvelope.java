package io.aetherdb.codec;

import io.aetherdb.api.typed.CollectionDefinition;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Encodes collection identity and key-codec version ahead of logical key bytes. */
public final class TypedKeyEnvelope {
    /** Fixed collection prefix length in bytes. */
    public static final int PREFIX_BYTES = 19;

    private TypedKeyEnvelope() {}

    /** Encodes a logical key in its collection namespace.
     * @param d collection definition
     * @param key logical key
     * @param <K> key type
     * @return physical database key */
    public static <K> byte[] encode(CollectionDefinition<K, ?> d, K key) {
        byte[] user = d.keyCodec().encode(key);
        if (user == null || user.length > d.keyCodec().maximumEncodedSize()) {
            throw new IllegalArgumentException("encoded key exceeds codec bound");
        }
        return ByteBuffer.allocate(PREFIX_BYTES + user.length).order(ByteOrder.BIG_ENDIAN)
                .put(prefix(d)).put(user).array();
    }

    /** Decodes a physical key after verifying its collection prefix.
     * @param d collection definition
     * @param physical physical database key
     * @param <K> key type
     * @return decoded logical key */
    public static <K> K decode(CollectionDefinition<K, ?> d, byte[] physical) {
        byte[] p = prefix(d);
        if (physical.length < p.length || !Arrays.equals(p, Arrays.copyOf(physical, p.length))) {
            throw new IllegalArgumentException("key is outside collection");
        }
        return d.keyCodec().decode(Arrays.copyOfRange(physical, p.length, physical.length));
    }

    /** Builds the physical namespace prefix for a collection.
     * @param d collection definition
     * @return fixed-size prefix */
    public static byte[] prefix(CollectionDefinition<?, ?> d) {
        return ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN).put((byte) 0x40)
                .putLong(d.id().value().getMostSignificantBits()).putLong(d.id().value().getLeastSignificantBits())
                .putShort((short) d.keyCodec().encodingVersion()).array();
    }

    /** Computes the exclusive upper bound for scanning one collection prefix.
     * @param d collection definition
     * @return shortest lexicographic successor of the prefix */
    public static byte[] prefixEnd(CollectionDefinition<?, ?> d) {
        byte[] end = prefix(d);
        for (int i = end.length - 1; i >= 0; i--) {
            if ((end[i] & 255) != 255) {
                end[i]++;
                return Arrays.copyOf(end, i + 1);
            }
        }
        throw new IllegalArgumentException("collection prefix has no successor");
    }
}
