package io.aetherdb.rpc.api;

/** Bound RPC service registry and transport lifecycle. */
public interface RpcServer extends AutoCloseable {
    /** Registers one unique operation before or while serving. */
    void register(RpcOperationDescriptor operation, RpcHandler handler);

    /** Returns the actual bound endpoint, including an assigned ephemeral port. */
    RpcEndpoint endpoint();

    /** Gracefully stops admission and releases the listener. */
    @Override
    void close();
}
