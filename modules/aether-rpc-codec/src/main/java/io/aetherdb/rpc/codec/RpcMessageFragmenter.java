package io.aetherdb.rpc.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Splits one bounded unary message into contiguous v1 frames. */
public final class RpcMessageFragmenter {
    private RpcMessageFragmenter() {}
    /** Splits a bounded request or response into contiguous frames.
     * @param type request or response frame type
     * @param streamId positive stream identity
     * @param code operation or status code
     * @param invocationId invocation identity
     * @param timeoutMillis request timeout, ignored for responses
     * @param message complete message bytes
     * @param maximumPayload negotiated frame payload limit
     * @return immutable ordered frame list */
    public static List<RpcFrame> fragment(RpcFrameType type, long streamId, int code, UUID invocationId,
            int timeoutMillis, byte[] message, int maximumPayload) {
        if (type != RpcFrameType.REQUEST && type != RpcFrameType.RESPONSE) throw new IllegalArgumentException("only request/response messages are fragmented");
        if (message == null || message.length > RpcFrameHeaderV1.MAX_MESSAGE_BYTES || maximumPayload < 1
                || maximumPayload > RpcFrameHeaderV1.MAX_FRAME_PAYLOAD) throw new IllegalArgumentException("invalid fragmentation limits");
        if (message.length == 0) return List.of(frame(type, streamId, code, invocationId, timeoutMillis, message, 0, 0, true, true));
        List<RpcFrame> result = new ArrayList<>();
        for (int offset = 0; offset < message.length; offset += maximumPayload) {
            int length = Math.min(maximumPayload, message.length - offset);
            result.add(frame(type, streamId, code, invocationId, timeoutMillis,
                    Arrays.copyOfRange(message, offset, offset + length), message.length, offset, offset == 0, offset + length == message.length));
        }
        return List.copyOf(result);
    }
    private static RpcFrame frame(RpcFrameType type, long stream, int code, UUID invocation, int timeout,
            byte[] payload, int total, int offset, boolean begin, boolean end) {
        int flags = (begin ? RpcFrameHeaderV1.BEGIN : 0) | (end ? RpcFrameHeaderV1.END : 0);
        return new RpcFrame(new RpcFrameHeaderV1(type, flags, stream, code, payload.length,
                begin ? total : 0, offset, type == RpcFrameType.REQUEST && begin ? timeout : 0, 0, invocation), payload);
    }
}
