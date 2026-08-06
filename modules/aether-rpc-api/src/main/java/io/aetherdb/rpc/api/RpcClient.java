package io.aetherdb.rpc.api;

import java.util.concurrent.CompletableFuture;

/** Multiplexed unary RPC client. */
public interface RpcClient extends AutoCloseable {
    /** Submits one bounded call and returns its asynchronous terminal response. */
    CompletableFuture<RpcResponse> call(RpcEndpoint peer, RpcOperationDescriptor operation,
                                        byte[] body, RpcCallOptions options);
    /** Drains and releases owned connections. */ @Override void close();
}
