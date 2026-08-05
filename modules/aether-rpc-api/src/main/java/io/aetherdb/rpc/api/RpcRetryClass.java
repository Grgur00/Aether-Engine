package io.aetherdb.rpc.api;

/** Retry-safety classification attached to an RPC operation. */
public enum RpcRetryClass {
    /** The client must not retry automatically. */
    NEVER,
    /** Repeating the operation is intrinsically safe. */
    IDEMPOTENT,
    /** Retrying is safe only when the server deduplicates the command identity. */
    DEDUP_REQUIRED
}
