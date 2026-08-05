package io.aetherdb.engine;

import io.aetherdb.api.AetherDatabase;

/** An Aether database that exposes low-overhead operation telemetry. */
public interface MeteredAetherDatabase extends AetherDatabase {
    /**
     * Collects the current telemetry values.
     *
     * @return an immutable point-in-time metrics snapshot
     */
    DatabaseMetrics metrics();

    /** Starts a fresh collection interval without modifying database contents. */
    void resetMetrics();
}
