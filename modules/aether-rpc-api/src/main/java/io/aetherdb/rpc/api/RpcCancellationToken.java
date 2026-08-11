package io.aetherdb.rpc.api;

/** Best-effort cooperative cancellation signal for a dispatched server request. */
public interface RpcCancellationToken {
    /** Reports whether cancellation has been requested. */
    boolean isCancelled();

    /** Registers a callback, invoking it immediately when already cancelled. */
    void onCancel(Runnable callback);

    /** Throws when cancellation has been requested. */
    default void throwIfCancelled() {
        if (isCancelled()) throw new RpcCancelledException("RPC request cancelled");
    }
}
