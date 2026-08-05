package io.aetherdb.rpc.transport;

/** Thread-safe bounded connection-level byte-credit accounting. */
public final class RpcFlowController {
    /** Largest permitted connection-level receive window. */
    public static final long MAXIMUM_WINDOW = 256L * 1024 * 1024;
    private long credit;
    /** Creates a controller with initial receive credit.
     * @param initialCredit initial window in bytes */
    public RpcFlowController(long initialCredit) {
        if (initialCredit < 1024 * 1024 || initialCredit > MAXIMUM_WINDOW) throw new IllegalArgumentException("invalid initial receive window");
        credit = initialCredit;
    }
    /** Attempts to reserve receive-window bytes atomically.
     * @param bytes non-negative reservation size
     * @return {@code true} when sufficient credit was reserved */
    public synchronized boolean tryReserve(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must be non-negative");
        if (bytes > credit) return false; credit -= bytes; return true;
    }
    /** Returns processed bytes to the receive window.
     * @param delta positive credit increment */
    public synchronized void update(int delta) {
        if (delta <= 0 || credit > MAXIMUM_WINDOW - delta) throw new IllegalArgumentException("window update overflow");
        credit += delta;
    }
    /** Returns unreserved receive capacity.
     * @return available byte credit */
    public synchronized long availableCredit() { return credit; }
}
