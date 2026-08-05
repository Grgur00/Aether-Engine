package io.aetherdb.api;

/** Requested persistent write barrier; in-memory engines report no performed barrier. */
public enum DurabilityMode {
    /** Return after appending to the process-visible WAL buffer. */ ASYNC_WAL,
    /** Share a storage synchronization barrier with concurrently admitted writes. */ GROUP_SYNC,
    /** Perform a dedicated durability barrier before returning. */ SYNC
}
