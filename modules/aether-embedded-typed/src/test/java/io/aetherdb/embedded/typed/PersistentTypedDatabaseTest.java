package io.aetherdb.embedded.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aetherdb.api.typed.*;
import io.aetherdb.codec.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class PersistentTypedDatabaseTest {
    @TempDir Path temp;

    @Test
    void typedValueSurvivesPublicPathBasedReopen() {
        var definition =
                CollectionDefinition.of(
                        CollectionId.of("11111111-1111-1111-1111-111111111111"),
                        "values",
                        BuiltInKeyCodecs.utf8String(),
                        BuiltInValueCodecs.utf8String(
                                UUID.fromString("22222222-2222-2222-2222-222222222222")));
        Path root = temp.resolve("db");
        try (var db = AetherEmbedded.open(root)) {
            db.collection(definition).put("key", "value");
        }
        try (var db = AetherEmbedded.open(root)) {
            assertEquals("value", db.collection(definition).get("key").requireValue());
        }
    }
}
