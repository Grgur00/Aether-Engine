package io.aetherdb.rpc.transport;

/** Thread-safe bounded connection-level byte-credit accounting. */
public final class RpcFlowController {
    public static final long MAXIMUM_WINDOW = 256L * 1024 * 1024;
    private long credit;
    public RpcFlowController(long initialCredit) {
        if (initialCredit < 1024 * 1024 || initialCredit > MAXIMUM_WINDOW) throw new IllegalArgumentException("invalid initial receive window");
        credit = initialCredit;
    }
    public synchronized boolean tryReserve(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must be non-negative");
        if (bytes > credit) return false; credit -= bytes; return true;
    }
    public synchronized void update(int delta) {
        if (delta <= 0 || credit > MAXIMUM_WINDOW - delta) throw new IllegalArgumentException("window update overflow");
        credit += delta;
    }
    public synchronized long availableCredit() { return credit; }
}
