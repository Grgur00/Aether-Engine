package io.aetherdb.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.engine.Aether;
import io.aetherdb.api.typed.CollectionCapability;
import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.codec.BuiltInKeyCodecs;
import io.aetherdb.codec.BuiltInValueCodecs;
import io.aetherdb.codec.CollectionMetadata;
import io.aetherdb.codec.TypedKeyEnvelope;
import io.aetherdb.codec.TypedValueEnvelope;
import io.aetherdb.codec.generated.CanonicalRecordReader;
import io.aetherdb.codec.generated.CanonicalRecordWriter;
import io.aetherdb.codec.generated.WireType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DatabaseWorkspaceTest {
    private static final CollectionDefinition<UUID, String> NOTES = new CollectionDefinition<>(
            CollectionId.of("7629a9f3-81a7-4346-b68f-cc273e154560"),
            "persistent-notes",
            BuiltInKeyCodecs.uuid(),
            BuiltInValueCodecs.utf8String(
                    UUID.fromString("fdd89bea-06ec-4231-bff3-b26047a3b132")),
            Set.of(
                    CollectionCapability.POINT_READ,
                    CollectionCapability.POINT_WRITE,
                    CollectionCapability.RANGE_SCAN));

    @Test void insertFormsKeepSchemaFieldsWithoutCopyingTheSelectedRecordValues() {
        assertThat(RecordDialog.blankStructuredValues(
                "16:id:string=post-2001\n"
                        + "17:authorId:string=usr-1001\n"
                        + "18:content:string=Existing post\n"
                        + "19:createdAt:instant=2025-03-01T10:00:00Z\n"
                        + "20:likes:long=42\n"
                        + "21:published:bool=true\n"))
                .isEqualTo(
                        "16:id:string=\n"
                                + "17:authorId:string=\n"
                                + "18:content:string=\n"
                                + "19:createdAt:instant=\n"
                                + "20:likes:long=0\n"
                                + "21:published:bool=false\n");
    }
    @Test void addEditRenameAndDeleteAreReflectedInSortedRows() {
        try (DatabaseWorkspace workspace = new DatabaseWorkspace(Aether.openInMemory())) {
            workspace.put("z", "last"); workspace.put("a", "first");
            assertThat(workspace.rows()).extracting(DatabaseWorkspace.Row::key).containsExactly("a", "z");

            workspace.edit("a", "b", "changed");
            assertThat(workspace.contains("a")).isFalse();
            assertThat(workspace.rows()).containsExactly(
                    new DatabaseWorkspace.Row("b", "changed", 7),
                    new DatabaseWorkspace.Row("z", "last", 4));

            assertThat(workspace.delete("b")).isTrue();
            assertThat(workspace.delete("b")).isFalse();
            assertThat(workspace.size()).isEqualTo(1);
        }
    }

    @Test void renameCannotOverwriteAnotherKnownKey() {
        try (DatabaseWorkspace workspace = new DatabaseWorkspace(Aether.openInMemory())) {
            workspace.put("a", "1"); workspace.put("b", "2");
            assertThatThrownBy(() -> workspace.edit("a", "b", "replacement"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("new key already exists");
            assertThat(workspace.rows()).extracting(DatabaseWorkspace.Row::value).containsExactly("1", "2");
        }
    }

    @Test void valuesUseUtf8AndReportEncodedByteLength() {
        try (DatabaseWorkspace workspace = new DatabaseWorkspace(Aether.openInMemory())) {
            workspace.put("ključ", "č");
            assertThat(workspace.rows()).containsExactly(new DatabaseWorkspace.Row("ključ", "č", 2));
        }
    }

    @Test void attachedWorkspaceDiscoversApplicationDataAndDoesNotCloseDatabase() {
        var database = Aether.openInMemory();
        database.put("outside".getBytes(java.nio.charset.StandardCharsets.UTF_8), "project".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
            assertThat(workspace.rows()).containsExactly(new DatabaseWorkspace.Row("outside", "project", 7));
        }
        assertThat(database.isClosed()).isFalse(); database.close();
    }

    @Test void slashDelimitedKeysExposeAGroupAndFieldForTheWorkbench() {
        var profileField = new DatabaseWorkspace.Row("social/profile/usr-1001/bio", "Compiler pioneer", 16);
        var rootField = new DatabaseWorkspace.Row("health", "ready", 5);

        assertThat(profileField.group()).isEqualTo("social/profile/usr-1001");
        assertThat(profileField.field()).isEqualTo("bio");
        assertThat(rootField.group()).isEqualTo("(root)");
        assertThat(rootField.field()).isEqualTo("health");
    }

    @Test void typedEnvelopesAreReadableWithoutApplicationCodecs() {
        UUID collection = UUID.fromString("7629a9f3-81a7-4346-b68f-cc273e154560");
        UUID note = UUID.fromString("8a58e35b-b46b-4ea5-9505-bc9da9c4466c");
        byte[] key = ByteBuffer.allocate(35)
                .put((byte) 0x40)
                .putLong(collection.getMostSignificantBits())
                .putLong(collection.getLeastSignificantBits())
                .putShort((short) 1)
                .putLong(note.getMostSignificantBits())
                .putLong(note.getLeastSignificantBits())
                .array();
        byte[] payload = "Visible note".getBytes(StandardCharsets.UTF_8);
        byte[] value = ByteBuffer.allocate(40 + payload.length)
                .put("AETV".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) 40)
                .putLong(1L)
                .putLong(2L)
                .putInt(1)
                .putInt(0)
                .putInt(payload.length)
                .putInt(0)
                .put(payload)
                .array();

        try (var database = Aether.openInMemory()) {
            database.put(key, value);
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                assertThat(workspace.rows()).containsExactly(new DatabaseWorkspace.Row(
                        "collection/" + collection + "/" + note,
                        "Visible note",
                        value.length));
            }
        }
    }

    @Test void registeredNotesCanBeEditedWithoutDamagingTheirTypedEnvelope() {
        UUID noteId = UUID.fromString("8a58e35b-b46b-4ea5-9505-bc9da9c4466c");
        byte[] key = TypedKeyEnvelope.encode(NOTES, noteId);
        String displayedKey = "collection/" + NOTES.id().value() + "/" + noteId;

        try (var database = Aether.openInMemory()) {
            database.put(
                    key,
                    TypedValueEnvelope.encode(NOTES.valueCodec(), "Before"));
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                workspace.rows();
                assertThat(workspace.canEdit(displayedKey)).isTrue();
                assertThat(workspace.keyEditable(displayedKey)).isFalse();
                workspace.edit(displayedKey, displayedKey, "After");
            }

            assertThat(TypedValueEnvelope.decode(
                    NOTES.valueCodec(), database.get(key).value()))
                    .isEqualTo("After");
        }
    }

    @Test void sixteenByteNoteTextIsNotMistakenForAUuid() {
        UUID noteId = UUID.fromString("8a58e35b-b46b-4ea5-9505-bc9da9c4466c");
        byte[] key = TypedKeyEnvelope.encode(NOTES, noteId);
        String sixteenBytes = "sixteen-char-msg";

        try (var database = Aether.openInMemory()) {
            database.put(
                    key,
                    TypedValueEnvelope.encode(NOTES.valueCodec(), sixteenBytes));
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                assertThat(workspace.rows())
                        .extracting(DatabaseWorkspace.Row::value)
                        .containsExactly(sixteenBytes);
            }
        }
    }

    @Test void workbenchAddsTypedEntriesUsingAnExistingCollectionTemplate() {
        UUID templateKey = UUID.fromString("8a58e35b-b46b-4ea5-9505-bc9da9c4466c");
        UUID newKey = UUID.fromString("6c721f21-aad1-4c70-9889-9aa09bb231cf");
        try (var database = Aether.openInMemory()) {
            database.put(
                    TypedKeyEnvelope.encode(NOTES, templateKey),
                    TypedValueEnvelope.encode(NOTES.valueCodec(), "Template"));
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                workspace.rows();
                String displayedKey = workspace.addTypedEntry(
                        "collection/" + NOTES.id().value() + "/" + templateKey,
                        newKey.toString(),
                        "Added in Workbench");
                assertThat(displayedKey)
                        .isEqualTo("collection/" + NOTES.id().value() + "/" + newKey);
                assertThat(workspace.rows())
                        .extracting(DatabaseWorkspace.Row::value)
                        .containsExactly("Added in Workbench", "Template");
            }
        }
    }

    @Test void generatedRecordsCanBeEditedWithoutTheirApplicationClasses() {
        UUID collection = UUID.fromString("7a39f7c1-d995-4b08-a866-4526e175e94f");
        byte[] key = ByteBuffer.allocate(24)
                .put((byte) 0x40)
                .putLong(collection.getMostSignificantBits())
                .putLong(collection.getLeastSignificantBits())
                .putShort((short) 1)
                .put("ada01".getBytes(StandardCharsets.UTF_8))
                .array();
        CanonicalRecordWriter writer = new CanonicalRecordWriter(4096);
        writer.field(16, WireType.STRING_UTF8, CanonicalRecordWriter.string("Ada", 100));
        writer.field(17, WireType.SIGNED_VARINT, CanonicalRecordWriter.signedLong(1));
        byte[] value = typedEnvelope(
                UUID.fromString("6553812e-0d82-4078-bfa2-5818d8fe670d"), writer.finish());
        String displayedKey = "collection/" + collection + "/ada01";

        try (var database = Aether.openInMemory()) {
            database.put(key, value);
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                assertThat(workspace.rows()).extracting(DatabaseWorkspace.Row::value)
                        .containsExactly("16:string=Ada\n17:long=1\n");
                assertThat(workspace.canEdit(displayedKey)).isTrue();
                workspace.edit(displayedKey, displayedKey, "16:string=Ada Lovelace\n17:long=2\n");
            }

            byte[] edited = database.get(key).value();
            assertThat(ByteBuffer.wrap(edited).getInt(24)).isEqualTo(1);
            byte[] payload = java.util.Arrays.copyOfRange(edited, 40, edited.length);
            CanonicalRecordReader reader = new CanonicalRecordReader(payload, 4096);
            assertThat(reader.next()).isTrue();
            assertThat(reader.fieldId()).isEqualTo(16);
            assertThat(reader.stringValue(100)).isEqualTo("Ada Lovelace");
            assertThat(reader.next()).isTrue();
            assertThat(reader.signedLongValue()).isEqualTo(2);
            assertThat(reader.next()).isFalse();
        }
    }

    @Test void durableDescriptorMetadataProvidesUniversalFieldNamesAndIsNotShownAsData() {
        UUID collection = UUID.fromString("7a39f7c1-d995-4b08-a866-4526e175e94f");
        UUID schema = UUID.fromString("6553812e-0d82-4078-bfa2-5818d8fe670d");
        byte[] descriptor = ("AETHER_SCHEMA_DESCRIPTOR_V1\n"
                + "schemaId=" + schema + "\n"
                + "version=1\n"
                + "type=example.SocialPost\n"
                + "payloadFormat=1\n"
                + "unknownFields=SKIP\n"
                + "field=16|id|STRING|64\n"
                + "field=17|authorId|STRING|64\n"
                + "field=18|content|STRING|8192\n")
                .getBytes(StandardCharsets.UTF_8);
        CollectionMetadata metadata = new CollectionMetadata(
                new CollectionId(collection), "social-posts", "aether.key.utf8", 1,
                new byte[32], schema, 1, new byte[32], descriptor);
        byte[] key = ByteBuffer.allocate(26)
                .put((byte) 0x40).putLong(collection.getMostSignificantBits())
                .putLong(collection.getLeastSignificantBits()).putShort((short) 1)
                .put("post-01".getBytes(StandardCharsets.UTF_8)).array();
        CanonicalRecordWriter writer = new CanonicalRecordWriter(4096);
        writer.field(16, WireType.STRING_UTF8, CanonicalRecordWriter.string("post-01", 64));
        writer.field(17, WireType.STRING_UTF8, CanonicalRecordWriter.string("usr-01", 64));
        writer.field(18, WireType.STRING_UTF8, CanonicalRecordWriter.string("Original", 8192));

        try (var database = Aether.openInMemory()) {
            database.put(metadata.key(), metadata.encode());
            database.put(key, typedEnvelope(schema, writer.finish()));
            try (DatabaseWorkspace workspace = new DatabaseWorkspace(database, false)) {
                assertThat(workspace.rows()).containsExactly(new DatabaseWorkspace.Row(
                        "collection/" + collection + "/post-01",
                        "16:id:string=post-01\n17:authorId:string=usr-01\n"
                                + "18:content:string=Original\n",
                        database.get(key).value().length));
                assertThat(workspace.size()).isEqualTo(1);
                workspace.edit(
                        "collection/" + collection + "/post-01",
                        "collection/" + collection + "/post-01",
                        "16:id:string=post-01\n17:authorId:string=usr-01\n"
                                + "18:content:string=Edited universally\n");
            }
            CanonicalRecordReader reader = new CanonicalRecordReader(
                    java.util.Arrays.copyOfRange(database.get(key).value(), 40,
                            database.get(key).value().length), 4096);
            reader.next(); reader.next(); reader.next();
            assertThat(reader.stringValue(8192)).isEqualTo("Edited universally");
        }
    }

    private static byte[] typedEnvelope(UUID schemaId, byte[] payload) {
        return ByteBuffer.allocate(40 + payload.length)
                .put("AETV".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) 40)
                .putLong(schemaId.getMostSignificantBits())
                .putLong(schemaId.getLeastSignificantBits())
                .putInt(1)
                .putInt(0)
                .putInt(payload.length)
                .putInt(0)
                .put(payload)
                .array();
    }
}
