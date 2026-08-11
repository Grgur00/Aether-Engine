package io.aetherdb.rpc.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental bounded RPC v1 decoder for arbitrarily split or coalesced transport reads. */
public final class RpcFrameDecoder {
    private final int maximumFramePayload;
    private final byte[] header = new byte[RpcFrameHeaderV1.HEADER_LENGTH];
    private int headerBytes;
    private byte[] payload;
    private int payloadBytes;

    /** Creates a decoder with the negotiated per-frame payload bound. */
    public RpcFrameDecoder(int maximumFramePayload) {
        if (maximumFramePayload < 1 || maximumFramePayload > RpcFrameHeaderV1.MAX_FRAME_PAYLOAD) {
            throw new IllegalArgumentException("invalid maximum frame payload");
        }
        this.maximumFramePayload = maximumFramePayload;
    }

    /** Consumes all available bytes and returns every complete verified frame in wire order. */
    public List<RpcFrame> feed(ByteBuffer input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        List<RpcFrame> completed = new ArrayList<>();
        while (input.hasRemaining()) {
            if (headerBytes < header.length) {
                int copied = Math.min(input.remaining(), header.length - headerBytes);
                input.get(header, headerBytes, copied);
                headerBytes += copied;
                if (headerBytes < header.length) break;
                int length = validateHeaderAndPayloadLength();
                payload = new byte[length];
                payloadBytes = 0;
                if (length == 0) completed.add(complete());
            } else {
                int copied = Math.min(input.remaining(), payload.length - payloadBytes);
                input.get(payload, payloadBytes, copied);
                payloadBytes += copied;
                if (payloadBytes == payload.length) completed.add(complete());
            }
        }
        return List.copyOf(completed);
    }

    /** Reports whether a partial header or payload is currently retained. */
    public boolean hasPartialFrame() {
        return headerBytes != 0;
    }

    /** Returns retained bytes for connection-level memory accounting. */
    public int retainedBytes() {
        return headerBytes + payloadBytes;
    }

    /** Drops any incomplete frame after terminal connection close. */
    public void reset() {
        headerBytes = 0;
        payload = null;
        payloadBytes = 0;
    }

    private int validateHeaderAndPayloadLength() {
        if (header[0] != 'A'
                || header[1] != 'E'
                || header[2] != 'R'
                || header[3] != 'P'
                || Byte.toUnsignedInt(header[4]) != 1
                || header[5] != 0) {
            throw new RpcProtocolException("unsupported RPC frame envelope");
        }
        int payloadLength = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(20);
        if (payloadLength < 0 || payloadLength > maximumFramePayload) {
            throw new RpcProtocolException("RPC frame exceeds negotiated payload limit");
        }
        return payloadLength;
    }

    private RpcFrame complete() {
        byte[] encoded = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, encoded, header.length, payload.length);
        try {
            return RpcFrameCodecV1.decode(encoded);
        } finally {
            headerBytes = 0;
            payload = null;
            payloadBytes = 0;
        }
    }
}
