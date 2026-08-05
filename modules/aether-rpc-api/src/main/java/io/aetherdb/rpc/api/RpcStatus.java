package io.aetherdb.rpc.api;

public enum RpcStatus {
    OK(0), CANCELLED(1), INVALID_ARGUMENT(2), DEADLINE_EXCEEDED(3), NOT_FOUND(4),
    ALREADY_EXISTS(5), RESOURCE_EXHAUSTED(6), FAILED_PRECONDITION(7),
    UNAVAILABLE(8), INTERNAL(9), UNAUTHENTICATED(10), PROTOCOL_ERROR(11);
    private final int code;
    RpcStatus(int code) { this.code = code; }
    public int code() { return code; }
    public static RpcStatus fromCode(int code) {
        for (RpcStatus status : values()) if (status.code == code) return status;
        throw new IllegalArgumentException("unknown RPC status: " + code);
    }
}
