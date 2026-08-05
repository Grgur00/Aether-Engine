package io.aetherdb.format.checksum;

import java.util.zip.CRC32C;

/** CRC32C plus Aether's LevelDB-compatible masking transform. */
public final class MaskedCrc32c {
    private static final int MASK_DELTA = 0xA282EAD8;
    private MaskedCrc32c() {}

    /**
     * Computes CRC32C over a byte-array range.
     *
     * @param bytes source bytes
     * @param offset first byte to include
     * @param length number of bytes to include
     * @return unmasked 32-bit checksum
     */
    public static int crc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    /**
     * Computes and masks CRC32C for durable storage.
     *
     * @param bytes source bytes
     * @param offset first byte to include
     * @param length number of bytes to include
     * @return masked checksum
     */
    public static int masked(byte[] bytes, int offset, int length) { return mask(crc(bytes, offset, length)); }

    /**
     * Applies the LevelDB-compatible rotation and delta transform.
     *
     * @param crc unmasked checksum
     * @return masked checksum
     */
    public static int mask(int crc) { return Integer.rotateRight(crc, 15) + MASK_DELTA; }

    /**
     * Reverses {@link #mask(int)}.
     *
     * @param stored masked checksum
     * @return original checksum
     */
    public static int unmask(int stored) { return Integer.rotateLeft(stored - MASK_DELTA, 15); }
}
