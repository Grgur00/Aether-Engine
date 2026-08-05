package io.aetherdb.embedded.typed;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.engine.Aether;
import java.nio.file.Path;

/** Supported entry point for embedded, type-safe Aether databases. */
public final class AetherEmbedded {
    private AetherEmbedded() {}

    /**
     * Creates an ephemeral typed database whose contents live only for this process.
     *
     * @return newly allocated in-memory database
     */
    public static TypedAetherDatabase openInMemory() {
        return adapt(Aether.openInMemory());
    }

    /**
     * Opens or creates a process-exclusive persistent typed database.
     *
     * @param directory database directory
     * @return database that owns the underlying persistent engine
     */
    public static TypedAetherDatabase open(Path directory) {
        return adapt(Aether.open(directory));
    }

    /**
     * Wraps a byte-oriented database in the typed API and transfers close ownership to the wrapper.
     *
     * @param database database to wrap
     * @return owning typed adapter
     */
    public static TypedAetherDatabase adapt(AetherDatabase database) {
        return new EmbeddedTypedDatabase(database, true);
    }
}
