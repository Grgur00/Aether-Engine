package io.aetherdb.sstable.manifest;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Exact 128-byte CURRENT pointer format. */
public final class CurrentFileV1 {
    /** Fixed encoded size. */
    public static final int BYTES = 128;

    private static final String PREFIX = "MANIFEST-";

    private CurrentFileV1() {}

    /**
     * Returns the canonical name for a positive manifest generation.
     *
     * @param generation positive manifest file number
     * @return canonical zero-padded filename
     */
    public static String manifestName(long generation) {
        if (generation <= 0)
            throw new IllegalArgumentException("manifest generation must be positive");
        return PREFIX + String.format(java.util.Locale.ROOT, "%020d.aeman", generation);
    }

    /**
     * Encodes a database-bound CURRENT publication.
     *
     * @param databaseId owning database identity
     * @param generation published manifest generation
     * @return canonical 128-byte value
     */
    public static byte[] encode(UUID databaseId, long generation) {
        if (databaseId == null) throw new IllegalArgumentException("database identity is required");
        String name = manifestName(generation);
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new byte[BYTES];
        ByteBuffer bytes = little(encoded);
        bytes.put("AETHCUR1".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) BYTES)
                .putInt(0)
                .putLong(generation)
                .putShort((short) nameBytes.length)
                .putShort((short) 0)
                .put(nameBytes)
                .position(92);
        putUuid(bytes, databaseId);
        bytes.putLong(generation).putInt(124, MaskedCrc32c.masked(encoded, 0, 124));
        return encoded;
    }

    /**
     * Decodes and validates CURRENT against the expected database identity.
     *
     * @param encoded exact fixed-size bytes
     * @param expectedDatabaseId expected owning database identity
     * @return validated publication pointer
     */
    public static Pointer decode(byte[] encoded, UUID expectedDatabaseId) {
        if (encoded == null || encoded.length != BYTES || expectedDatabaseId == null)
            throw corrupt("invalid CURRENT");
        if (little(encoded).getInt(124) != MaskedCrc32c.masked(encoded, 0, 124))
            throw corrupt("CURRENT checksum mismatch");
        ByteBuffer bytes = little(encoded);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, "AETHCUR1".getBytes(StandardCharsets.US_ASCII))
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != BYTES
                || bytes.getInt() != 0) throw corrupt("invalid CURRENT prefix");
        long fileNumber = bytes.getLong();
        int nameLength = Short.toUnsignedInt(bytes.getShort());
        if (bytes.getShort() != 0 || nameLength <= 0 || nameLength > 64)
            throw corrupt("invalid CURRENT name length");
        byte[] nameRegion = new byte[64];
        bytes.get(nameRegion);
        for (int index = nameLength; index < nameRegion.length; index++)
            if (nameRegion[index] != 0) throw corrupt("nonzero CURRENT name padding");
        String name = new String(nameRegion, 0, nameLength, StandardCharsets.US_ASCII);
        UUID databaseId = getUuid(bytes);
        long publicationGeneration = bytes.getLong();
        for (int index = 116; index < 124; index++)
            if (encoded[index] != 0) throw corrupt("nonzero CURRENT reserved byte");
        if (!databaseId.equals(expectedDatabaseId)
                || publicationGeneration != fileNumber
                || !name.equals(manifestName(fileNumber)))
            throw corrupt("inconsistent CURRENT publication");
        return new Pointer(fileNumber, name);
    }

    /**
     * Validated CURRENT target.
     *
     * @param generation published manifest generation
     * @param manifestName canonical manifest filename
     */
    public record Pointer(long generation, String manifestName) {}

    private static ByteBuffer little(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void putUuid(ByteBuffer target, UUID value) {
        target.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer source) {
        return new UUID(source.getLong(), source.getLong());
    }

    private static ManifestCorruptionException corrupt(String message) {
        return new ManifestCorruptionException(message);
    }
}
