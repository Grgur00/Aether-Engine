package io.aetherdb.embedded.typed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.codec.CollectionMetadata;
import io.aetherdb.codec.generated.GeneratedCodecs;
import io.aetherdb.engine.Aether;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

final class GeneratedRecordPersistenceTest {
    private static final CollectionId LEDGER =
            CollectionId.of("74ee12fd-6544-44fd-b858-2e51c4101066");

    @TempDir Path temporaryDirectory;

    @Test
    void generatedLedgerCodecWorksThroughPublicApiAcrossCloseAndReopen() {
        Path directory = temporaryDirectory.resolve("ledger-db");
        UUID accountId = UUID.fromString("1e67c216-1f2d-4ee7-8d52-c740b77fddc4");
        LedgerEntry entry =
                new LedgerEntry(
                        accountId, 12_345, "EUR", Instant.parse("2026-08-05T12:34:56.123456789Z"));

        try (var database = AetherEmbedded.open(directory)) {
            var entries =
                    database.defineCollection(
                            LEDGER, "ledger-entries", UUID.class, LedgerEntry.class);
            entries.put(entry.accountId(), entry);
        }

        try (var database = AetherEmbedded.open(directory)) {
            var entries =
                    database.defineCollection(
                            LEDGER, "ledger-entries", UUID.class, LedgerEntry.class);
            assertThat(entries.get(accountId).value()).contains(entry);
        }

        try (var database = Aether.open(directory);
                var cursor = database.scanAll()) {
            CollectionMetadata catalog = null;
            while (cursor.next()) {
                var decoded = CollectionMetadata.decode(cursor.key(), cursor.value());
                if (decoded.isPresent()) catalog = decoded.orElseThrow();
            }
            assertThat(catalog).isNotNull();
            assertThat(catalog.id()).isEqualTo(LEDGER);
            assertThat(catalog.name()).isEqualTo("ledger-entries");
            assertThat(new String(catalog.schemaDescriptor(), StandardCharsets.UTF_8))
                    .contains("field=16|accountId|UUID|16", "field=19|bookedAt|INSTANT|20");
        }
    }

    @Test
    void generatedBytesAndDescriptorFingerprintAreDeterministic() throws Exception {
        var codec = GeneratedCodecs.forRecord(LedgerEntry.class);
        LedgerEntry entry =
                new LedgerEntry(
                        UUID.fromString("1e67c216-1f2d-4ee7-8d52-c740b77fddc4"),
                        12_345,
                        "EUR",
                        Instant.parse("2026-08-05T12:34:56.123456789Z"));
        byte[] first = codec.encode(entry);
        byte[] second = codec.encode(entry);

        assertThat(second).isEqualTo(first);
        assertThat(new String(first, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("AER1");
        assertThat(HexFormat.of().formatHex(first))
                .isEqualTo(
                        "4145523101001800040000004700000002000000db3eee82"
                                + "100800101e67c2161f2d4ee78d52c740b77fddc4"
                                + "11020003f2c00112070003455552"
                                + "130a0009e0b799a70d959aef3a");

        String descriptorPath =
                "META-INF/aether/schemas/"
                        + codec.schemaId()
                        + "/"
                        + codec.currentSchemaVersion()
                        + ".aesch";
        byte[] descriptor;
        try (var input = LedgerEntry.class.getClassLoader().getResourceAsStream(descriptorPath)) {
            assertThat(input).isNotNull();
            descriptor = input.readAllBytes();
        }
        assertThat(codec.fingerprint())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(descriptor));
        assertThat(HexFormat.of().formatHex(codec.fingerprint()))
                .isEqualTo("303e87981ebec6231c207e185ff753766457c2fe4426a6f0e0649a41a03ed562");

        byte[] corrupted = first.clone();
        corrupted[corrupted.length - 1] ^= 1;
        assertThatThrownBy(() -> codec.decode(1, corrupted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RECORD_CRC_MISMATCH");
    }

    @Test
    void unrelatedSensorModelUsesItsOwnGeneratedSchema() {
        var codec = GeneratedCodecs.forRecord(SensorReading.class);
        SensorReading reading =
                new SensorReading(
                        "sensor-zg-7", Instant.parse("2026-08-05T13:00:00Z"), 24.75, 42, true);
        assertThat(codec.decode(codec.currentSchemaVersion(), codec.encode(reading)))
                .isEqualTo(reading);
        assertThat(codec.schemaId())
                .isNotEqualTo(GeneratedCodecs.forRecord(LedgerEntry.class).schemaId());
    }

    @Test
    void unrelatedSensorModelAlsoSurvivesCloseAndReopen() {
        Path directory = temporaryDirectory.resolve("sensor-db");
        CollectionId collection = CollectionId.of("c31dd2ca-94f3-4812-b3d7-df555e25caea");
        SensorReading reading =
                new SensorReading(
                        "sensor-zg-7", Instant.parse("2026-08-05T13:00:00Z"), 24.75, 42, true);

        try (var database = AetherEmbedded.open(directory)) {
            database.defineCollection(
                            collection, "sensor-readings", String.class, SensorReading.class)
                    .put(reading.sensorId(), reading);
        }
        try (var database = AetherEmbedded.open(directory)) {
            assertThat(
                            database.defineCollection(
                                            collection,
                                            "sensor-readings",
                                            String.class,
                                            SensorReading.class)
                                    .get(reading.sensorId())
                                    .value())
                    .contains(reading);
        }
    }

    @Test
    void generatedProviderAndResourceIndexArePublished() throws Exception {
        String indexPath = "META-INF/aether/generated-codecs.idx";
        try (var input = LedgerEntry.class.getClassLoader().getResourceAsStream(indexPath)) {
            assertThat(input).isNotNull();
            String index = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(index)
                    .contains(
                            LedgerEntry.class.getName(),
                            SensorReading.class.getName(),
                            "LedgerEntry_AetherCodecProvider",
                            "SensorReading_AetherCodecProvider");
        }
    }

    @Test
    void lockManagedTodoHasNoSourceIdsAndSurvivesReopen() {
        Path directory = temporaryDirectory.resolve("todo-db");
        CollectionId collection = CollectionId.of("d8e6bc6e-d5f7-422e-b986-a2181bf3db47");
        Todo todo = new Todo("1", "Buy milk", false);

        try (var database = AetherEmbedded.open(directory)) {
            database.defineCollection(collection, "todos", String.class, Todo.class)
                    .put(todo.id(), todo);
        }
        try (var database = AetherEmbedded.open(directory)) {
            assertThat(
                            database.defineCollection(collection, "todos", String.class, Todo.class)
                                    .get(todo.id())
                                    .value())
                    .contains(todo);
        }

        var codec = GeneratedCodecs.forRecord(Todo.class);
        assertThat(codec.schemaId()).hasToString("7c4d8b15-e87d-48fc-a5d0-8324fae35852");
        assertThat(HexFormat.of().formatHex(codec.fingerprint()))
                .isEqualTo("14179cf6b3163111835c90b23a29c8321e3cd0c36a895fb8c31582029fd78ddd");
    }

    @Test
    void namedCollectionNeedsNoApplicationManagedUuidAndSurvivesReopen() {
        Path directory = temporaryDirectory.resolve("friendly-todo-db");
        Todo todo = new Todo("1", "Buy milk", false);

        try (var database = AetherEmbedded.open(directory)) {
            var todos = database.defineCollection("todos", String.class, Todo.class);
            assertThat(todos.definition().id()).isEqualTo(CollectionId.fromName("todos"));
            todos.put(todo.id(), todo);
        }

        try (var database = AetherEmbedded.open(directory)) {
            assertThat(
                            database.defineCollection("todos", String.class, Todo.class)
                                    .get(todo.id())
                                    .value())
                    .contains(todo);
        }
    }
}
