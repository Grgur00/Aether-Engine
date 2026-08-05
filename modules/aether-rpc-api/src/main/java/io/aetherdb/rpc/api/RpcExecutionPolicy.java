package io.aetherdb.rpc.api;

/** Executor isolation class used when dispatching an RPC operation. */
public enum RpcExecutionPolicy {
    /** Lightweight control-plane work that must not block on storage. */
    CONTROL,
    /** Potentially blocking storage read work. */
    STORAGE_READ,
    /** Storage mutation work requiring write-path admission control. */
    STORAGE_WRITE
}
