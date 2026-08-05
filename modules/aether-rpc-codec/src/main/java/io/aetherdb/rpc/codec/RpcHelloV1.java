package io.aetherdb.rpc.codec;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Exact 192-byte application handshake payload v1.
 * @param role local connection role
 * @param clusterId cluster identity
 * @param nodeId node identity
 * @param sessionId process-session identity
 * @param connectionNonce non-zero connection nonce
 * @param maximumFramePayload negotiated frame payload limit
 * @param maximumMessageBytes negotiated reassembled message limit
 * @param maximumConcurrentStreams negotiated stream limit
 * @param initialReceiveWindowBytes initial connection receive credit
 * @param keepaliveIdleMillis idle duration before a keepalive probe
 * @param keepaliveTimeoutMillis keepalive response timeout
 * @param engineMajor engine major version
 * @param engineMinor engine minor version
 * @param enginePatch engine patch version
 * @param javaMajor Java feature version
 */
public record RpcHelloV1(Role role, UUID clusterId, UUID nodeId, UUID sessionId, long connectionNonce,
        int maximumFramePayload, int maximumMessageBytes, int maximumConcurrentStreams,
        int initialReceiveWindowBytes, int keepaliveIdleMillis, int keepaliveTimeoutMillis,
        int engineMajor, int engineMinor, int enginePatch, int javaMajor) {
    /** Exact encoded HELLO length. */
    public static final int ENCODED_LENGTH = 192;
    /** Connection role encoded in the handshake. */
    public enum Role {
        /** Peer that initiated the transport connection. */ DIALER(1),
        /** Peer that accepted the transport connection. */ ACCEPTOR(2);
        private final int code;
        Role(int code) { this.code = code; }
        int code() { return code; }
    }

    /** Validates identities, bounds, timeouts, and runtime compatibility fields. */
    public RpcHelloV1 {
        if (role == null || isZero(clusterId) || isZero(nodeId) || isZero(sessionId) || connectionNonce == 0
                || maximumFramePayload < 1 || maximumFramePayload > RpcFrameHeaderV1.MAX_FRAME_PAYLOAD
                || maximumMessageBytes < 0 || maximumMessageBytes > RpcFrameHeaderV1.MAX_MESSAGE_BYTES
                || maximumConcurrentStreams < 1 || maximumConcurrentStreams > 1024
                || initialReceiveWindowBytes < 1024 * 1024 || initialReceiveWindowBytes > 256 * 1024 * 1024
                || keepaliveIdleMillis <= 0 || keepaliveTimeoutMillis <= 0 || engineMajor < 0 || engineMinor < 0
                || enginePatch < 0 || javaMajor < 21) throw new IllegalArgumentException("invalid RPC HELLO values");
    }

    /** Encodes this handshake with compatibility fingerprint and checksum.
     * @return fixed-size HELLO payload */
    public byte[] encode() {
        byte[] result = new byte[ENCODED_LENGTH]; ByteBuffer bytes = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        bytes.put("AEHL".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) ENCODED_LENGTH);
        bytes.put((byte) 1).put((byte) role.code()).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        bytes.putLong(0).putLong(0); putUuid(bytes, clusterId); putUuid(bytes, nodeId); putUuid(bytes, sessionId);
        bytes.putLong(connectionNonce).putInt(maximumFramePayload).putInt(maximumMessageBytes).putInt(maximumConcurrentStreams);
        bytes.putInt(initialReceiveWindowBytes).putInt(keepaliveIdleMillis).putInt(keepaliveTimeoutMillis);
        bytes.putInt(engineMajor).putInt(engineMinor).putInt(enginePatch).putInt(javaMajor);
        bytes.put(compatibilityFingerprint()); bytes.position(188).putInt(MaskedCrc32c.masked(result, 0, 188)); return result;
    }

    /** Decodes and validates a complete HELLO payload.
     * @param encoded fixed-size handshake bytes
     * @return validated handshake */
    public static RpcHelloV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH) throw new RpcProtocolException("HELLO must be exactly 192 bytes");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN); byte[] magic = new byte[4]; bytes.get(magic);
        if (!Arrays.equals(magic, "AEHL".getBytes(StandardCharsets.US_ASCII)) || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != ENCODED_LENGTH || Byte.toUnsignedInt(bytes.get()) != 1)
            throw new RpcProtocolException("invalid HELLO header");
        int roleCode = Byte.toUnsignedInt(bytes.get()); Role role = roleCode == 1 ? Role.DIALER : roleCode == 2 ? Role.ACCEPTOR : null;
        if (role == null || bytes.getShort() != 0 || bytes.getShort() != 0 || bytes.getShort() != 0 || bytes.getLong() != 0 || bytes.getLong() != 0)
            throw new RpcProtocolException("unsupported HELLO flags or features");
        UUID cluster = getUuid(bytes), node = getUuid(bytes), session = getUuid(bytes); long nonce = bytes.getLong();
        int frame = bytes.getInt(), message = bytes.getInt(), streams = bytes.getInt(), window = bytes.getInt();
        int idle = bytes.getInt(), timeout = bytes.getInt(), major = bytes.getInt(), minor = bytes.getInt(), patch = bytes.getInt(), java = bytes.getInt();
        byte[] fingerprint = new byte[32]; bytes.get(fingerprint);
        for (int index = 160; index < 188; index++) if (encoded[index] != 0) throw new RpcProtocolException("nonzero HELLO reserved byte");
        int crc = ByteBuffer.wrap(encoded, 188, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (crc != MaskedCrc32c.masked(encoded, 0, 188)) throw new RpcProtocolException("HELLO checksum mismatch");
        RpcHelloV1 hello;
        try { hello = new RpcHelloV1(role, cluster, node, session, nonce, frame, message, streams, window, idle, timeout, major, minor, patch, java); }
        catch (IllegalArgumentException invalid) { throw new RpcProtocolException(invalid.getMessage()); }
        if (!MessageDigest.isEqual(fingerprint, compatibilityFingerprint())) throw new RpcProtocolException("HELLO compatibility mismatch");
        return hello;
    }

    /** Computes the frozen RPC v1 compatibility fingerprint.
     * @return newly allocated SHA-256 fingerprint */
    public static byte[] compatibilityFingerprint() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    "AETHER-RPC-COMPAT-V1|1.0|64|1,2,3,4,5,6,7,8,9|BEGIN=1,END=2|status-v1|hello-v1|crc32c-mask-v1"
                            .getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean isZero(UUID value) { return value == null || value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0; }
    private static void putUuid(ByteBuffer bytes, UUID value) { bytes.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }
}
