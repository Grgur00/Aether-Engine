package io.aetherdb.rpc.codec;

public enum RpcFrameType {
    HELLO(1, true), REQUEST(2, false), RESPONSE(3, false), CANCEL(4, false), WINDOW_UPDATE(5, true),
    PING(6, true), PONG(7, true), GOAWAY(8, true), CONNECTION_ERROR(9, true);
    private final int code; private final boolean control;
    RpcFrameType(int code, boolean control) { this.code = code; this.control = control; }
    public int code() { return code; }
    public boolean control() { return control; }
    public static RpcFrameType fromCode(int code) {
        for (RpcFrameType type : values()) if (type.code == code) return type;
        throw new RpcProtocolException("unknown frame type: " + code);
    }
}
