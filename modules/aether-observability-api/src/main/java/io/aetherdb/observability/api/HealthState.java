package io.aetherdb.observability.api;

/** Engine health state independent of any deployment platform. */
public enum HealthState {
    STARTING(false, false),
    RECOVERING(false, false),
    READ_ONLY_DEGRADED(true, false),
    SERVING(true, true),
    FOLLOWER_SERVING(true, false),
    LEADER_SERVING(true, true),
    DRAINING(true, false),
    UNHEALTHY(false, false);

    private final boolean readsAllowed;
    private final boolean writesAllowed;

    HealthState(boolean readsAllowed, boolean writesAllowed) {
        this.readsAllowed = readsAllowed;
        this.writesAllowed = writesAllowed;
    }

    public boolean readsAllowed() {
        return readsAllowed;
    }

    public boolean writesAllowed() {
        return writesAllowed;
    }
}
