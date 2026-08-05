package io.aetherdb.sstable.filter;

import io.aetherdb.sstable.SSTableCorruptionException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Full-file Bloom filter v1 using 10 bits/key and seven probes. */
public final class BloomFilterV1 {
    /** Target filter bits allocated per distinct key. */
    public static final int BITS_PER_KEY = 10;
    /** Hash probes performed per lookup. */
    public static final int PROBES = 7;
    private BloomFilterV1() {}

    /** Builds a deterministic full-file filter from distinct key content.
     * @param keys keys to include
     * @return encoded filter bytes */
    public static byte[] build(Collection<byte[]> keys) {
        Map<Key, Boolean> unique = new LinkedHashMap<>();
        for (byte[] key : keys) unique.put(new Key(key), Boolean.TRUE);
        int bitCount = Math.max(64, ((unique.size() * BITS_PER_KEY + 7) / 8) * 8);
        byte[] bits = new byte[bitCount / 8];
        for (Key key : unique.keySet()) add(bits, bitCount, hash(key.bytes));
        ByteBuffer result = ByteBuffer.allocate(24 + bits.length).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(1).putInt(1).putInt(unique.size()).putInt(bitCount).putShort((short) BITS_PER_KEY)
                .put((byte) PROBES).put((byte) 0).putInt(bits.length).put(bits);
        return result.array();
    }

    /** Tests membership after validating the encoded filter header.
     * @param encoded encoded filter bytes
     * @param key candidate key
     * @return {@code false} only when the key is definitely absent */
    public static boolean mayContain(byte[] encoded, byte[] key) {
        if (encoded.length < 32) throw corrupt();
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt() != 1 || header.getInt() != 1) throw corrupt();
        int keyCount = header.getInt(), bitCount = header.getInt();
        if (keyCount < 0 || bitCount < 64 || bitCount % 8 != 0 || Short.toUnsignedInt(header.getShort()) != BITS_PER_KEY
                || Byte.toUnsignedInt(header.get()) != PROBES || header.get() != 0) throw corrupt();
        int byteLength = header.getInt();
        if (byteLength != bitCount / 8 || encoded.length != 24 + byteLength) throw corrupt();
        byte[] bits = java.util.Arrays.copyOfRange(encoded, 24, encoded.length);
        long hash = hash(key), delta = Long.rotateRight(hash, 17) | 1L;
        for (int probe = 0; probe < PROBES; probe++) {
            int bit = (int) Long.remainderUnsigned(hash, bitCount);
            if ((bits[bit >>> 3] & (1 << (bit & 7))) == 0) return false;
            hash += delta;
        }
        return true;
    }

    private static void add(byte[] bits, int bitCount, long hash) {
        long delta = Long.rotateRight(hash, 17) | 1L;
        for (int probe = 0; probe < PROBES; probe++) { int bit = (int) Long.remainderUnsigned(hash, bitCount); bits[bit >>> 3] |= (byte) (1 << (bit & 7)); hash += delta; }
    }

    // AetherHash64 v1 constants and avalanche are format compatibility state.
    /** Computes the frozen AetherHash64 v1 value.
     * @param key key bytes
     * @return 64-bit format-stable hash */
    public static long hash(byte[] key) {
        long hash = 0xCBF29CE484222325L ^ 0xA17E4E5D9B7C3F21L;
        for (byte value : key) { hash ^= Byte.toUnsignedLong(value); hash *= 0x100000001B3L; }
        hash ^= hash >>> 33; hash *= 0xff51afd7ed558ccdl; hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53l; return hash ^ (hash >>> 33);
    }
    private static SSTableCorruptionException corrupt() { return new SSTableCorruptionException("corrupt Bloom filter"); }
    private record Key(byte[] bytes) { Key { bytes = bytes.clone(); } @Override public boolean equals(Object o){return o instanceof Key k && java.util.Arrays.equals(bytes,k.bytes);}@Override public int hashCode(){return java.util.Arrays.hashCode(bytes);} }
}
