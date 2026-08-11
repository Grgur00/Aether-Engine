package io.aetherdb.rpc.codec;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/** Strict contiguous fragment assembler with a pre-allocation message bound. */
public final class RpcMessageAssembler {
    private final int maximumMessageBytes;
    private ByteArrayOutputStream output;
    private RpcFrameType type;
    private long streamId;
    private int code;
    private UUID invocation;
    private int declaredLength;

    /**
     * Creates an assembler with a pre-allocation message bound.
     *
     * @param maximumMessageBytes negotiated message limit
     */
    public RpcMessageAssembler(int maximumMessageBytes) {
        if (maximumMessageBytes < 0 || maximumMessageBytes > RpcFrameHeaderV1.MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("invalid message limit");
        this.maximumMessageBytes = maximumMessageBytes;
    }

    /**
     * Accepts the next contiguous frame.
     *
     * @param frame next frame in stream order
     * @return complete message bytes on END, otherwise {@code null}
     */
    public byte[] accept(RpcFrame frame) {
        RpcFrameHeaderV1 header = frame.header();
        if (header.beginsMessage()) {
            if (output != null)
                throw new RpcProtocolException("message BEGIN while assembly is active");
            if (header.messageLength() > maximumMessageBytes)
                throw new RpcProtocolException("message exceeds negotiated limit");
            type = header.type();
            streamId = header.streamId();
            code = header.code();
            invocation = header.invocationId();
            declaredLength = header.messageLength();
            output = new ByteArrayOutputStream(Math.min(declaredLength, 64 * 1024));
        } else if (output == null) throw new RpcProtocolException("fragment without BEGIN");
        if (header.type() != type
                || header.streamId() != streamId
                || header.code() != code
                || !header.invocationId().equals(invocation))
            throw new RpcProtocolException("fragment identity changed");
        if (header.fragmentOffset() != output.size()
                || (long) output.size() + header.payloadLength() > declaredLength)
            throw new RpcProtocolException("noncontiguous or oversized fragment");
        output.writeBytes(frame.payload());
        if (!header.endsMessage()) return null;
        if (output.size() != declaredLength)
            throw new RpcProtocolException("END before declared message length");
        byte[] completed = output.toByteArray();
        reset();
        return completed;
    }

    /** Discards any partially assembled message and identity state. */
    public void reset() {
        output = null;
        type = null;
        invocation = null;
        declaredLength = 0;
        streamId = 0;
        code = 0;
    }

    /**
     * Reports whether a message is currently incomplete.
     *
     * @return {@code true} between BEGIN and END
     */
    public boolean isAssembling() {
        return output != null;
    }
}
