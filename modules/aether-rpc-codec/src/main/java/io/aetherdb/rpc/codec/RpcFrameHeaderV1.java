package io.aetherdb.rpc.codec;

import java.util.Objects;
import java.util.UUID;

/**
 * Exact semantic fields of the 64-byte big-endian RPC frame header.
 * @param type frame kind
 * @param flags BEGIN/END bit flags
 * @param streamId stream identity, or zero for control frames
 * @param code operation or status code
 * @param payloadLength payload bytes in this frame
 * @param messageLength total message bytes declared by BEGIN
 * @param fragmentOffset payload offset within the message
 * @param timeoutMillis request timeout declared by REQUEST BEGIN
 * @param creditDelta bytes returned by WINDOW_UPDATE
 * @param invocationId end-to-end invocation identity
 */
public record RpcFrameHeaderV1(RpcFrameType type, int flags, long streamId, int code, int payloadLength,
        int messageLength, int fragmentOffset, int timeoutMillis, int creditDelta, UUID invocationId) {
    /** Exact encoded header length. */
    public static final int HEADER_LENGTH = 64;
    /** Flag marking the first message fragment. */
    public static final int BEGIN = 0x01;
    /** Flag marking the final message fragment. */
    public static final int END = 0x02;
    /** Maximum payload carried by one frame. */
    public static final int MAX_FRAME_PAYLOAD = 1024 * 1024;
    /** Maximum reassembled message length. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    /** Invocation marker required by control frames without an invocation. */
    public static final UUID ZERO_INVOCATION = new UUID(0, 0);

    /** Validates cross-field framing, stream, timeout, and flow-control invariants. */
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
    /** Reports whether this frame begins a message.
     * @return {@code true} when BEGIN is set */
    public boolean beginsMessage() { return (flags & BEGIN) != 0; }
    /** Reports whether this frame ends a message.
     * @return {@code true} when END is set */
    public boolean endsMessage() { return (flags & END) != 0; }
}
