package io.aetherdb.rpc.api;

/** Exactly-once bounded response completion surface for a server handler. */
public interface RpcResponder {
    /** Completes successfully with the supplied body. */ void success(byte[] body);
    /** Completes with a stable non-OK status and bounded safe detail. */ void fail(RpcStatus status, String detail);
}
