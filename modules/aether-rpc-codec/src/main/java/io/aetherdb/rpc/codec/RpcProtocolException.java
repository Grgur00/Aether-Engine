package io.aetherdb.rpc.codec;

public final class RpcProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RpcProtocolException(String message) { super(message); }
}
