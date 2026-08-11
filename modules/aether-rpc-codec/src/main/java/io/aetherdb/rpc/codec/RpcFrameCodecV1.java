package io.aetherdb.rpc.codec;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Exact 64-byte-header frame encoder/decoder with payload CRC coverage. */
public final class RpcFrameCodecV1 {
    private static final byte[] MAGIC = "AERP".getBytes(StandardCharsets.US_ASCII);

    private RpcFrameCodecV1() {}

    /**
     * Encodes one frame with a big-endian header and masked CRC32C.
     *
     * @param frame frame to encode
     * @return complete wire frame
     */
    public static byte[] encode(RpcFrame frame) {
        RpcFrameHeaderV1 header = frame.header();
        byte[] payload = frame.payload();
        byte[] encoded = new byte[RpcFrameHeaderV1.HEADER_LENGTH + payload.length];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        bytes.put(MAGIC)
                .put((byte) 1)
                .put((byte) 0)
                .put((byte) header.type().code())
                .put((byte) header.flags());
        bytes.putLong(header.streamId())
                .putInt(header.code())
                .putInt(payload.length)
                .putInt(header.messageLength());
        bytes.putInt(header.fragmentOffset())
                .putInt(header.timeoutMillis())
                .putInt(header.creditDelta());
        bytes.putLong(header.invocationId().getMostSignificantBits())
                .putLong(header.invocationId().getLeastSignificantBits());
        bytes.putInt(0);
        bytes.position(64).put(payload);
        byte[] checksumInput = new byte[60 + payload.length];
        System.arraycopy(encoded, 0, checksumInput, 0, 60);
        System.arraycopy(payload, 0, checksumInput, 60, payload.length);
        ByteBuffer.wrap(encoded)
                .order(ByteOrder.BIG_ENDIAN)
                .position(60)
                .putInt(MaskedCrc32c.masked(checksumInput, 0, checksumInput.length));
        return encoded;
    }

    /**
     * Decodes and validates one complete wire frame.
     *
     * @param encoded header and payload bytes
     * @return decoded frame
     */
    public static RpcFrame decode(byte[] encoded) {
        if (encoded == null || encoded.length < 64)
            throw new RpcProtocolException("incomplete RPC frame header");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        bytes.get(magic);
        if (!Arrays.equals(magic, MAGIC)
                || Byte.toUnsignedInt(bytes.get()) != 1
                || Byte.toUnsignedInt(bytes.get()) != 0)
            throw new RpcProtocolException("unsupported RPC frame envelope");
        RpcFrameType type = RpcFrameType.fromCode(Byte.toUnsignedInt(bytes.get()));
        int flags = Byte.toUnsignedInt(bytes.get());
        long stream = bytes.getLong();
        int code = bytes.getInt();
        int payloadLength = bytes.getInt();
        int messageLength = bytes.getInt();
        int offset = bytes.getInt();
        int timeout = bytes.getInt();
        int credit = bytes.getInt();
        UUID invocation = new UUID(bytes.getLong(), bytes.getLong());
        if (bytes.getInt() != 0) throw new RpcProtocolException("nonzero frame reserved field");
        int storedCrc = bytes.getInt();
        if (payloadLength < 0
                || payloadLength > RpcFrameHeaderV1.MAX_FRAME_PAYLOAD
                || encoded.length != 64 + payloadLength)
            throw new RpcProtocolException("invalid RPC payload length");
        byte[] payload = Arrays.copyOfRange(encoded, 64, encoded.length);
        byte[] checksumInput = new byte[60 + payload.length];
        System.arraycopy(encoded, 0, checksumInput, 0, 60);
        System.arraycopy(payload, 0, checksumInput, 60, payload.length);
        if (storedCrc != MaskedCrc32c.masked(checksumInput, 0, checksumInput.length))
            throw new RpcProtocolException("RPC frame checksum mismatch");
        try {
            return new RpcFrame(
                    new RpcFrameHeaderV1(
                            type,
                            flags,
                            stream,
                            code,
                            payloadLength,
                            messageLength,
                            offset,
                            timeout,
                            credit,
                            invocation),
                    payload);
        } catch (IllegalArgumentException invalid) {
            throw new RpcProtocolException(invalid.getMessage());
        }
    }
}
