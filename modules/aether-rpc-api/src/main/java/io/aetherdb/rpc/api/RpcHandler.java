package io.aetherdb.rpc.api;

/** Application handler for one registered unary RPC operation. */
@FunctionalInterface
public interface RpcHandler {
    /** Handles one admitted request and completes its responder exactly once. */
    void handle(RpcServerRequest request, RpcResponder responder);
}
