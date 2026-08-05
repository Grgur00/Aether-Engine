package io.aetherdb.replication.log;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Exact 256-byte RLOG-IDENTITY local persistent format.
 * @param clusterId owning cluster identity
 * @param nodeId local node identity
 * @param creationEpochMillis identity creation time in epoch milliseconds
 */
public record ReplicatedLogIdentityV1(UUID clusterId, UUID nodeId, long creationEpochMillis) {
    /** Encoded identity length in bytes. */
    public static final int ENCODED_LENGTH = 256;
    private static final byte[] MAGIC = "AETHRLI1".getBytes(StandardCharsets.US_ASCII);
    /** Validates non-zero identities and creation time. */
    public ReplicatedLogIdentityV1 {
        if (isZero(clusterId) || isZero(nodeId) || creationEpochMillis < 0) throw new IllegalArgumentException("invalid replicated-log identity");
    }
    /** Encodes this identity with its compatibility fingerprint and checksum.
     * @return newly allocated identity bytes */
    public byte[] encode() {
        byte[] result = new byte[ENCODED_LENGTH]; ByteBuffer bytes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(MAGIC).putShort((short) 1).putShort((short) ENCODED_LENGTH).putInt(0);
        putUuid(bytes, clusterId); putUuid(bytes, nodeId);
        bytes.putLong(1).putLong(134_217_728L).putLong(268_435_456L).putLong(67_108_864L);
        bytes.putInt(192).putInt(4096).putInt(8).putShort((short) 1).putShort((short) 1);
        bytes.put(compatibilityFingerprint()).putLong(creationEpochMillis);
        bytes.position(252).putInt(MaskedCrc32c.masked(result, 0, 252)); return result;
    }
    /** Decodes and validates a replicated-log identity.
     * @param encoded durable identity bytes
     * @return decoded identity */
    public static ReplicatedLogIdentityV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH) throw new IllegalArgumentException("RLOG-IDENTITY must be exactly 256 bytes");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[8]; bytes.get(magic);
        if (!Arrays.equals(magic, MAGIC) || Short.toUnsignedInt(bytes.getShort()) != 1 || Short.toUnsignedInt(bytes.getShort()) != 256 || bytes.getInt() != 0)
            throw new IllegalArgumentException("invalid RLOG-IDENTITY header");
        UUID cluster = getUuid(bytes), node = getUuid(bytes);
        if (bytes.getLong() != 1 || bytes.getLong() != 134_217_728L || bytes.getLong() != 268_435_456L || bytes.getLong() != 67_108_864L
                || bytes.getInt() != 192 || bytes.getInt() != 4096 || bytes.getInt() != 8 || bytes.getShort() != 1 || bytes.getShort() != 1)
            throw new IllegalArgumentException("unsupported replicated-log format vector");
        byte[] fingerprint = new byte[32]; bytes.get(fingerprint); long created = bytes.getLong();
        for (int index = 136; index < 252; index++) if (encoded[index] != 0) throw new IllegalArgumentException("nonzero RLOG-IDENTITY reserved byte");
        int crc = ByteBuffer.wrap(encoded, 252, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crc != MaskedCrc32c.masked(encoded, 0, 252) || !MessageDigest.isEqual(fingerprint, compatibilityFingerprint()))
            throw new IllegalArgumentException("RLOG-IDENTITY integrity failure");
        return new ReplicatedLogIdentityV1(cluster, node, created);
    }
    /** Computes the supported replicated-log format fingerprint.
     * @return newly allocated SHA-256 fingerprint */
    public static byte[] compatibilityFingerprint() {
        ByteBuffer vector = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        vector.put("AETHER-RLOG-COMPAT-V1".getBytes(StandardCharsets.US_ASCII)).putLong(1).putLong(134_217_728L)
                .putLong(268_435_456L).putLong(67_108_864L).putInt(192).putInt(4096).putInt(8)
                .putShort((short) 1).putShort((short) 1).putShort((short) 1).putShort((short) 1)
                .putShort((short) 1).putShort((short) 1).putShort((short) 1);
        try { return MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(vector.array(), vector.position())); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean isZero(UUID id) { return id == null || id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0; }
    private static void putUuid(ByteBuffer bytes, UUID id) { bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }
}
