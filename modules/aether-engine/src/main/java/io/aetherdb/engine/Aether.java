package io.aetherdb.engine;

import io.aetherdb.api.AetherDatabase;

import java.nio.file.Path;

/** Supported composition root for byte-oriented Aether database implementations. */
public final class Aether {
    private Aether() {}

    /**
     * Creates an ephemeral database whose contents are discarded on close.
     *
     * @return newly allocated in-memory database
     */
    public static AetherDatabase openInMemory() {
        return new InMemoryAetherDatabase();
    }

    /**
     * Opens or creates one process-exclusive persistent local database.
     *
     * @param directory database directory
     * @return opened persistent database
     */
    public static AetherDatabase open(Path directory) {
        return PersistentAetherDatabase.open(directory);
    }

    /**
     * Creates an ephemeral database with operation latency and throughput metrics.
     *
     * @return newly allocated metered in-memory database
     */
    public static MeteredAetherDatabase openInMemoryWithMetrics() {
        return instrument(openInMemory());
    }

    /**
     * Opens a persistent local database with operation latency and throughput metrics.
     *
     * @param directory database directory
     * @return opened metered persistent database
     */
    public static MeteredAetherDatabase openWithMetrics(Path directory) {
        return instrument(open(directory));
    }

    /**
     * Adds operation metrics to an existing database and transfers close ownership to the returned
     * decorator.
     *
     * @param database database to instrument
     * @return owning metered decorator
     */
    public static MeteredAetherDatabase instrument(AetherDatabase database) {
        return new DefaultMeteredAetherDatabase(database);
    }
}
