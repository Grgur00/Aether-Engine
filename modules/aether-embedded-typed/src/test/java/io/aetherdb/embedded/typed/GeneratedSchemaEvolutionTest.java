package io.aetherdb.embedded.typed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.typed.CollectionId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

class GeneratedSchemaEvolutionTest {
    private static final CollectionId COLLECTION =
            CollectionId.of("8c056594-cf10-496f-846a-28c9811454fd");

    @TempDir Path temporaryDirectory;

    @Test
    void compatibleGeneratedV2ReadsV1AndDurablyBlocksOlderWriters() {
        Path directory = temporaryDirectory.resolve("evolution-db");
        try (var database = AetherEmbedded.open(directory)) {
            database.defineCollection(COLLECTION, "profiles", Long.class, EvolvingProfileV1.class)
                    .put(1L, new EvolvingProfileV1("Ada"));
        }

        Instant changed = Instant.parse("2026-08-06T10:00:00Z");
        try (var database = AetherEmbedded.open(directory)) {
            var profiles =
                    database.defineCollection(
                            COLLECTION, "profiles", Long.class, EvolvingProfileV2.class);
            assertThat(profiles.get(1L).requireValue())
                    .isEqualTo(new EvolvingProfileV2("Ada", Optional.empty()));
            profiles.put(1L, new EvolvingProfileV2("Ada Lovelace", Optional.of(changed)));
        }

        try (var database = AetherEmbedded.open(directory)) {
            var oldWriter =
                    database.defineCollection(
                            COLLECTION, "profiles", Long.class, EvolvingProfileV1.class);
            assertThatThrownBy(() -> oldWriter.put(1L, new EvolvingProfileV1("erased")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OLDER_WRITER_REJECTED")
                    .hasMessageContaining("version 2");
        }

        try (var database = AetherEmbedded.open(directory)) {
            var profiles =
                    database.defineCollection(
                            COLLECTION, "profiles", Long.class, EvolvingProfileV2.class);
            assertThat(profiles.get(1L).requireValue())
                    .isEqualTo(new EvolvingProfileV2("Ada Lovelace", Optional.of(changed)));
        }
    }
}
