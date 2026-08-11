package io.aetherdb.rpc.api;

/** Caller behavior when a bounded outbound RPC queue cannot accept more bytes. */
public enum RpcBackpressureMode {
    /** Fail immediately with resource exhaustion. */
    FAIL_FAST,
    /** Wait only until the call's monotonic deadline. */
    WAIT_UNTIL_DEADLINE
}
