package io.aetherdb.rpc.api;

/** Indicates cooperative cancellation of a local or remote RPC operation. */
public final class RpcCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a cancellation failure. */
    public RpcCancelledException(String message) {
        super(message);
    }
}
