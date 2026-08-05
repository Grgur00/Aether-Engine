package io.aetherdb.rpc.codec;

/** Stable frame kinds carried by RPC v1 headers. */
public enum RpcFrameType {
    /** Connection capability and identity handshake. */ HELLO(1, true),
    /** Application request fragment. */ REQUEST(2, false),
    /** Application response fragment. */ RESPONSE(3, false),
    /** Stream cancellation notification. */ CANCEL(4, false),
    /** Connection receive-credit replenishment. */ WINDOW_UPDATE(5, true),
    /** Keepalive or reachability probe. */ PING(6, true),
    /** Reply to a ping. */ PONG(7, true),
    /** Graceful connection-drain notification. */ GOAWAY(8, true),
    /** Terminal connection-level protocol failure. */ CONNECTION_ERROR(9, true);
    private final int code; private final boolean control;
    RpcFrameType(int code, boolean control) { this.code = code; this.control = control; }
    /** Returns the wire code.
     * @return stable frame-type code */
    public int code() { return code; }
    /** Reports whether this frame belongs to stream zero.
     * @return {@code true} for connection-control frames */
    public boolean control() { return control; }
    /** Resolves a frame type from its wire code.
     * @param code encoded type
     * @return corresponding frame type */
    public static RpcFrameType fromCode(int code) {
        for (RpcFrameType type : values()) if (type.code == code) return type;
        throw new RpcProtocolException("unknown frame type: " + code);
    }
}
