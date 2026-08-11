package io.aetherdb.io;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Exact fixed checkpoint publication metadata.
 *
 * @param databaseId preserved source database identity
 * @param checkpointSequence maximum sequence stored by the checkpoint
 * @param creationEpochMillis checkpoint creation time
 * @param sourceReadViewGeneration retained source version generation
 * @param sourceManifestNumber source manifest generation
 * @param checkpointManifestNumber standalone checkpoint manifest generation
 * @param sstableFileCount copied table count
 * @param totalSstableBytes copied table bytes
 * @param compatibilityFingerprint 32-byte format fingerprint
 */
public record CheckpointMetadataV1(
        UUID databaseId,
        long checkpointSequence,
        long creationEpochMillis,
        long sourceReadViewGeneration,
        long sourceManifestNumber,
        long checkpointManifestNumber,
        long sstableFileCount,
        long totalSstableBytes,
        byte[] compatibilityFingerprint) {
    /** Exact encoded bytes. */
    public static final int ENCODED_BYTES = 256;

    /** Validates fields and takes a defensive fingerprint copy. */
    public CheckpointMetadataV1 {
        if (databaseId == null
                || checkpointSequence < 0
                || creationEpochMillis < 0
                || sourceReadViewGeneration <= 0
                || sourceManifestNumber <= 0
                || checkpointManifestNumber <= 0
                || sstableFileCount < 0
                || totalSstableBytes < 0
                || compatibilityFingerprint == null
                || compatibilityFingerprint.length != 32)
            throw new IllegalArgumentException("invalid checkpoint metadata");
        compatibilityFingerprint = compatibilityFingerprint.clone();
    }

    /**
     * Returns the format fingerprint.
     *
     * @return defensive 32-byte copy
     */
    @Override
    public byte[] compatibilityFingerprint() {
        return compatibilityFingerprint.clone();
    }

    /**
     * Encodes the canonical fixed-size metadata.
     *
     * @return exact 256-byte value
     */
    public byte[] encode() {
        byte[] encoded = new byte[ENCODED_BYTES];
        ByteBuffer bytes = little(encoded);
        bytes.put("AETHCHK1".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) ENCODED_BYTES)
                .putInt(0)
                .putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits())
                .putLong(checkpointSequence)
                .putLong(creationEpochMillis)
                .putLong(sourceReadViewGeneration)
                .putLong(sourceManifestNumber)
                .putLong(checkpointManifestNumber)
                .putLong(sstableFileCount)
                .putLong(totalSstableBytes)
                .put(compatibilityFingerprint);
        bytes.putInt(252, MaskedCrc32c.masked(encoded, 0, 252));
        return encoded;
    }

    /**
     * Decodes and validates exact checkpoint metadata.
     *
     * @param encoded exact 256-byte value
     * @return validated metadata
     */
    public static CheckpointMetadataV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_BYTES)
            throw new IllegalArgumentException("invalid checkpoint metadata length");
        if (little(encoded).getInt(252) != MaskedCrc32c.masked(encoded, 0, 252))
            throw new IllegalArgumentException("checkpoint metadata checksum mismatch");
        ByteBuffer bytes = little(encoded);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, "AETHCHK1".getBytes(StandardCharsets.US_ASCII))
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != ENCODED_BYTES
                || bytes.getInt() != 0)
            throw new IllegalArgumentException("invalid checkpoint metadata prefix");
        UUID databaseId = new UUID(bytes.getLong(), bytes.getLong());
        long sequence = bytes.getLong(), created = bytes.getLong();
        long sourceView = bytes.getLong(),
                sourceManifest = bytes.getLong(),
                checkpointManifest = bytes.getLong();
        long files = bytes.getLong(), tableBytes = bytes.getLong();
        byte[] fingerprint = new byte[32];
        bytes.get(fingerprint);
        for (int index = 120; index < 252; index++)
            if (encoded[index] != 0)
                throw new IllegalArgumentException("nonzero checkpoint metadata reserved byte");
        return new CheckpointMetadataV1(
                databaseId,
                sequence,
                created,
                sourceView,
                sourceManifest,
                checkpointManifest,
                files,
                tableBytes,
                fingerprint);
    }

    private static ByteBuffer little(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    }
}
