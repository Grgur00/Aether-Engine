package io.aetherdb.rpc.codec;

/** Non-reusing positive stream IDs with dialer odd / acceptor even parity. */
public final class RpcStreamIdAllocator {
    public enum Role { DIALER, ACCEPTOR }
    private long next;
    public RpcStreamIdAllocator(Role role) { next = role == Role.DIALER ? 1 : 2; }
    public synchronized long nextId() {
        if (next <= 0 || next > Long.MAX_VALUE - 2) throw new IllegalStateException("RPC stream IDs exhausted");
        long result = next; next += 2; return result;
    }
}
