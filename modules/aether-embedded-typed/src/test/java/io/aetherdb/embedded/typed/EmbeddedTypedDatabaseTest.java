package io.aetherdb.embedded.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.api.typed.ReadResult;
import io.aetherdb.api.typed.TypedWriteResult;
import io.aetherdb.codec.BuiltInKeyCodecs;
import io.aetherdb.codec.BuiltInValueCodecs;
import io.aetherdb.codec.CollectionMetadata;
import io.aetherdb.engine.Aether;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddedTypedDatabaseTest {
    @Test void typedPointsBatchAndSnapshotPreserveSemantics() {
        var definition = CollectionDefinition.of(
                CollectionId.of("11111111-1111-1111-1111-111111111111"),
                "users",
                BuiltInKeyCodecs.signedLong(),
                BuiltInValueCodecs.utf8String(
                        UUID.fromString("22222222-2222-2222-2222-222222222222")));
        try (var database = AetherEmbedded.openInMemory()) {
            var users = database.collection(definition);
            assertTrue(users.get(1L) instanceof ReadResult.NotFound<?>);
            assertTrue(users.put(1L, "one") instanceof TypedWriteResult.Applied);
            try (var snapshot = database.snapshot()) {
                var old = snapshot.collection(definition);
                users.put(1L, "new");
                assertEquals("one", old.get(1L).requireValue());
            }
            var batch = database.batch().put(users, 2L, "two").delete(users, 1L);
            var result = database.write(batch);
            assertEquals(2, ((TypedWriteResult.Applied) result).operationCount());
            assertEquals("two", users.get(2L).requireValue());
            assertTrue(users.get(1L) instanceof ReadResult.NotFound<?>);
        }
    }

    @Test void durableMinimumWriterVersionRejectsAnOlderApplication() {
        UUID schemaId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        CollectionId collectionId = CollectionId.of("44444444-4444-4444-4444-444444444444");
        var oldDefinition = CollectionDefinition.of(collectionId, "values",
                BuiltInKeyCodecs.signedLong(), BuiltInValueCodecs.utf8String(schemaId));
        CollectionMetadata oldMetadata = CollectionMetadata.from(oldDefinition, java.util.Optional.empty());
        CollectionMetadata newerMetadata = new CollectionMetadata(
                collectionId, "values", oldMetadata.keyCodecId(), oldMetadata.keyEncodingVersion(),
                oldMetadata.keyFingerprint(), schemaId, 2, new byte[32],
                "newer descriptor".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AetherDatabase raw = Aether.openInMemory();
        raw.put(newerMetadata.key(), newerMetadata.encode());
        try (var database = new EmbeddedTypedDatabase(raw, true)) {
            var collection = database.collection(oldDefinition);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> collection.put(1L, "must-not-overwrite"));
            assertTrue(failure.getMessage().contains("OLDER_WRITER_REJECTED"));
            assertTrue(raw.get(io.aetherdb.codec.TypedKeyEnvelope.encode(oldDefinition, 1L)).isFound() == false);
        }
    }
}
