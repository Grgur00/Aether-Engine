package io.aetherdb.engine;

import io.aetherdb.api.AetherDatabase;
import java.nio.file.Path;

/** Supported composition root. */
public final class Aether {
    private Aether() {}

    public static AetherDatabase openInMemory() {
        return new InMemoryAetherDatabase();
    }

    /** Opens or creates one process-exclusive persistent local database. */
    public static AetherDatabase open(Path directory) {
        return PersistentAetherDatabase.open(directory);
    }
}
