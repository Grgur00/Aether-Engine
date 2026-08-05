package io.aetherdb.format.checksum;

import java.util.zip.CRC32C;

/** CRC32C plus Aether's LevelDB-compatible masking transform. */
public final class MaskedCrc32c {
    private static final int MASK_DELTA = 0xA282EAD8;
    private MaskedCrc32c() {}

    public static int crc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    public static int masked(byte[] bytes, int offset, int length) { return mask(crc(bytes, offset, length)); }
    public static int mask(int crc) { return Integer.rotateRight(crc, 15) + MASK_DELTA; }
    public static int unmask(int stored) { return Integer.rotateLeft(stored - MASK_DELTA, 15); }
}
