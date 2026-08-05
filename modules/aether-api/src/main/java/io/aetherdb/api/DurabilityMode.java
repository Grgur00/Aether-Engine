package io.aetherdb.api;

/** Requested persistent write barrier; in-memory engines report no performed barrier. */
public enum DurabilityMode { ASYNC_WAL, GROUP_SYNC, SYNC }
