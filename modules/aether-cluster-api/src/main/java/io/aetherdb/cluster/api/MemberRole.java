package io.aetherdb.cluster.api;

public enum MemberRole {
    VOTER(1), STAGED_NONVOTER(2);
    private final int code;
    MemberRole(int code) { this.code = code; }
    public int code() { return code; }
    public static MemberRole fromCode(int code) {
        for (MemberRole role : values()) if (role.code == code) return role;
        throw new IllegalArgumentException("unknown member role: " + code);
    }
}
