package io.aetherdb.engine;

import io.aetherdb.api.AetherDatabase;

/** Supported composition root. */
public final class Aether {
    private Aether() {}

    public static AetherDatabase openInMemory() {
        return new InMemoryAetherDatabase();
    }
}
