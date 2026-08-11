package io.aetherdb.rpc.api;

/** Stable status codes carried by RPC response envelopes. */
public enum RpcStatus {
    /** Operation completed successfully. */
    OK(0),
    /** Caller or server cancelled the operation. */
    CANCELLED(1),
    /** Request arguments were invalid. */
    INVALID_ARGUMENT(2),
    /** Deadline elapsed before completion. */
    DEADLINE_EXCEEDED(3),
    /** Requested resource was not found. */
    NOT_FOUND(4),
    /** Requested resource already exists. */
    ALREADY_EXISTS(5),
    /** A bounded resource or admission limit was exhausted. */
    RESOURCE_EXHAUSTED(6),
    /** Current system state does not permit the operation. */
    FAILED_PRECONDITION(7),
    /** Service is temporarily unavailable. */
    UNAVAILABLE(8),
    /** Server encountered an unexpected failure. */
    INTERNAL(9),
    /** Peer did not provide valid authentication. */
    UNAUTHENTICATED(10),
    /** Peer violated the RPC wire protocol. */
    PROTOCOL_ERROR(11);
    private final int code;

    RpcStatus(int code) {
        this.code = code;
    }

    /**
     * Returns the stable integer carried on the wire.
     *
     * @return protocol status code
     */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code to its status.
     *
     * @param code protocol status code
     * @return corresponding status
     * @throws IllegalArgumentException if the code is unknown
     */
    public static RpcStatus fromCode(int code) {
        for (RpcStatus status : values()) if (status.code == code) return status;
        throw new IllegalArgumentException("unknown RPC status: " + code);
    }
}
