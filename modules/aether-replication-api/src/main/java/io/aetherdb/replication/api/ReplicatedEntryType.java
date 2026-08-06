package io.aetherdb.replication.api;

/** Stable replicated-log entry kinds. */
public enum ReplicatedEntryType {
    /** Leader-term commitment without state mutation. */ NOOP(1),
    /** Atomic state-machine command. */ COMMAND(2),
    /** Stable or joint membership configuration. */ CONFIGURATION(3),
    /** Replicated coordination barrier without user mutation. */ BARRIER(4);
    private final int code;
    ReplicatedEntryType(int code) { this.code = code; }
    /** Returns the persistent type code. */ public int code() { return code; }
    /** Resolves a persistent type code. */
    public static ReplicatedEntryType fromCode(int code) {
        for (ReplicatedEntryType type : values()) if (type.code == code) return type;
        throw new IllegalArgumentException("unknown replicated entry type: " + code);
    }
}
