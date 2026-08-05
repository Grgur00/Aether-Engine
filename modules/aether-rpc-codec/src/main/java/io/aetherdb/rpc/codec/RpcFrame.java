package io.aetherdb.rpc.codec;

/** Immutable decoded frame. */
public record RpcFrame(RpcFrameHeaderV1 header, byte[] payload) {
    public RpcFrame { if (header == null || payload == null || payload.length != header.payloadLength()) throw new IllegalArgumentException("frame payload length mismatch"); payload = payload.clone(); }
    @Override public byte[] payload() { return payload.clone(); }
}
