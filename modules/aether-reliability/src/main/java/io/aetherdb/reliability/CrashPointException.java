package io.aetherdb.reliability;

/** Exception thrown by deterministic test crash points. */
public final class CrashPointException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String crashPointId;

    public CrashPointException(String crashPointId) {
        super("crash point triggered: " + crashPointId);
        this.crashPointId = crashPointId;
    }

    public String crashPointId() {
        return crashPointId;
    }
}
