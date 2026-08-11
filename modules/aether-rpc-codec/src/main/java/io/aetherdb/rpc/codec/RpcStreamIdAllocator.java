package io.aetherdb.rpc.codec;

/** Non-reusing positive stream IDs with dialer odd / acceptor even parity. */
public final class RpcStreamIdAllocator {
    /** Local connection role controlling stream-ID parity. */
    public enum Role {
        /** Initiating peer allocates odd stream IDs. */
        DIALER,
        /** Accepting peer allocates even stream IDs. */
        ACCEPTOR
    }

    private long next;

    /**
     * Creates an allocator for one side of a connection.
     *
     * @param role local connection role
     */
    public RpcStreamIdAllocator(Role role) {
        next = role == Role.DIALER ? 1 : 2;
    }

    /**
     * Allocates the next non-reusable stream identity.
     *
     * @return positive stream ID with role-specific parity
     */
    public synchronized long nextId() {
        if (next <= 0 || next > Long.MAX_VALUE - 2)
            throw new IllegalStateException("RPC stream IDs exhausted");
        long result = next;
        next += 2;
        return result;
    }
}
