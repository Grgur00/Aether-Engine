package io.aetherdb.rpc.codec;

/** Immutable decoded frame.
 * @param header validated semantic frame header
 * @param payload copied payload bytes */
public record RpcFrame(RpcFrameHeaderV1 header, byte[] payload) {
    /** Validates header/payload length consistency and copies the payload. */
    public RpcFrame { if (header == null || payload == null || payload.length != header.payloadLength()) throw new IllegalArgumentException("frame payload length mismatch"); payload = payload.clone(); }
    /** Returns the frame payload.
     * @return defensive payload copy */
    @Override public byte[] payload() { return payload.clone(); }
}
