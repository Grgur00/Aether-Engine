package io.aetherdb.rpc.codec;

import java.util.Objects;
import java.util.UUID;

/** Exact semantic fields of the 64-byte big-endian RPC frame header. */
public record RpcFrameHeaderV1(RpcFrameType type, int flags, long streamId, int code, int payloadLength,
        int messageLength, int fragmentOffset, int timeoutMillis, int creditDelta, UUID invocationId) {
    public static final int HEADER_LENGTH = 64;
    public static final int BEGIN = 0x01;
    public static final int END = 0x02;
    public static final int MAX_FRAME_PAYLOAD = 1024 * 1024;
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    public static final UUID ZERO_INVOCATION = new UUID(0, 0);

    public RpcFrameHeaderV1 {
        Objects.requireNonNull(type); Objects.requireNonNull(invocationId);
        if ((flags & ~(BEGIN | END)) != 0 || streamId < 0 || payloadLength < 0 || payloadLength > MAX_FRAME_PAYLOAD
                || messageLength < 0 || messageLength > MAX_MESSAGE_BYTES || fragmentOffset < 0
                || timeoutMillis < 0 || creditDelta < 0) throw new IllegalArgumentException("invalid RPC frame fields");
        if (type.control() != (streamId == 0) && type != RpcFrameType.CANCEL)
            throw new IllegalArgumentException("invalid stream ID for frame type");
        if (type == RpcFrameType.CANCEL && streamId == 0) throw new IllegalArgumentException("CANCEL requires a stream");
        if ((flags & BEGIN) == 0 && messageLength != 0) throw new IllegalArgumentException("only BEGIN declares message length");
        if (type != RpcFrameType.REQUEST && timeoutMillis != 0) throw new IllegalArgumentException("timeout is request-only");
        if (type != RpcFrameType.WINDOW_UPDATE && creditDelta != 0) throw new IllegalArgumentException("credit is WINDOW_UPDATE-only");
        if (type == RpcFrameType.WINDOW_UPDATE && creditDelta == 0) throw new IllegalArgumentException("WINDOW_UPDATE credit must be positive");
        if (type.control() && type != RpcFrameType.HELLO && type != RpcFrameType.GOAWAY
                && type != RpcFrameType.CONNECTION_ERROR && !invocationId.equals(ZERO_INVOCATION))
            throw new IllegalArgumentException("control invocation ID must be zero");
    }
    public boolean beginsMessage() { return (flags & BEGIN) != 0; }
    public boolean endsMessage() { return (flags & END) != 0; }
}
