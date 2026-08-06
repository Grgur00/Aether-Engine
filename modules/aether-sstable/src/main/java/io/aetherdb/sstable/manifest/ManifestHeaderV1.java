package io.aetherdb.sstable.manifest;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Fixed manifest header stored at the start of a 4 KiB reserved region.
 * @param databaseId owning database identity
 * @param manifestFileNumber positive manifest generation
 * @param creationEpochMillis diagnostic creation timestamp
 * @param initialNextFileNumber diagnostic initial allocation counter
 * @param initialLastSequence diagnostic initial sequence counter */
public record ManifestHeaderV1(UUID databaseId, long manifestFileNumber, long creationEpochMillis,
                               long initialNextFileNumber, long initialLastSequence) {
    /** Fixed header bytes. */ public static final int HEADER_BYTES = 96;
    /** Reserved header-region bytes. */ public static final int HEADER_REGION_BYTES = 4_096;

    /** Validates durable header fields. */
    public ManifestHeaderV1 {
        if (databaseId == null || manifestFileNumber <= 0 || creationEpochMillis < 0
                || initialNextFileNumber <= 0 || initialLastSequence < 0) throw new IllegalArgumentException("invalid manifest header");
    }

    /** Encodes the complete zero-padded header region.
     * @return canonical 4 KiB region */
    public byte[] encodeRegion() {
        byte[] region = new byte[HEADER_REGION_BYTES]; ByteBuffer bytes = little(region);
        bytes.put("AETHMAN1".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) HEADER_BYTES)
                .putInt(0).putLong(databaseId.getMostSignificantBits()).putLong(databaseId.getLeastSignificantBits())
                .putLong(manifestFileNumber).putInt(1).putInt(7).putLong(creationEpochMillis)
                .putLong(initialNextFileNumber).putLong(initialLastSequence);
        bytes.putInt(92, MaskedCrc32c.masked(region, 0, 92)); return region;
    }

    /** Decodes the complete header region and verifies all reserved bytes.
     * @param region exact 4 KiB input
     * @return validated header */
    public static ManifestHeaderV1 decodeRegion(byte[] region) {
        if (region == null || region.length != HEADER_REGION_BYTES) throw corrupt("invalid manifest header region");
        if (little(region).getInt(92) != MaskedCrc32c.masked(region, 0, 92)) throw corrupt("manifest header checksum mismatch");
        ByteBuffer bytes = little(region); byte[] magic = new byte[8]; bytes.get(magic);
        if (!Arrays.equals(magic, "AETHMAN1".getBytes(StandardCharsets.US_ASCII))
                || Short.toUnsignedInt(bytes.getShort()) != 1 || Short.toUnsignedInt(bytes.getShort()) != HEADER_BYTES
                || bytes.getInt() != 0) throw corrupt("invalid manifest header prefix");
        UUID databaseId = new UUID(bytes.getLong(), bytes.getLong()); long fileNumber = bytes.getLong();
        if (bytes.getInt() != 1 || bytes.getInt() != 7) throw corrupt("unsupported manifest comparator or level count");
        long created = bytes.getLong(), next = bytes.getLong(), last = bytes.getLong();
        for (int index = 72; index < 92; index++) if (region[index] != 0) throw corrupt("nonzero manifest header reserved byte");
        for (int index = HEADER_BYTES; index < region.length; index++) if (region[index] != 0) throw corrupt("nonzero manifest region padding");
        try { return new ManifestHeaderV1(databaseId, fileNumber, created, next, last); }
        catch (IllegalArgumentException failure) { throw new ManifestCorruptionException("invalid manifest header fields", failure); }
    }

    private static ByteBuffer little(byte[] value) { return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN); }
    private static ManifestCorruptionException corrupt(String message) { return new ManifestCorruptionException(message); }
}
