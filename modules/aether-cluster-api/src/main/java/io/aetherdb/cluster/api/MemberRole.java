package io.aetherdb.cluster.api;

/** Durable role assigned to a cluster member. */
public enum MemberRole {
    /** Member participates in elections and commit quorums. */ VOTER(1),
    /** Member receives state but does not yet vote. */ STAGED_NONVOTER(2);
    private final int code;
    MemberRole(int code) { this.code = code; }
    /** Returns the encoded role.
     * @return stable integer used by the cluster wire format */
    public int code() { return code; }
    /**
     * Resolves a durable role code.
     *
     * @param code encoded role code
     * @return corresponding member role
     */
    public static MemberRole fromCode(int code) {
        for (MemberRole role : values()) if (role.code == code) return role;
        throw new IllegalArgumentException("unknown member role: " + code);
    }
}
