package io.aetherdb.rpc.codec;

/** Signals a peer-visible violation of the RPC framing or message protocol. */
public final class RpcProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a protocol failure.
     *
     * @param message violation description
     */
    public RpcProtocolException(String message) {
        super(message);
    }
}
