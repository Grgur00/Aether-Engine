package io.aetherdb.examples.notes;

import io.aetherdb.api.typed.CollectionCapability;
import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.api.typed.TypedAetherCollection;
import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.api.typed.TypedWriteResult;
import io.aetherdb.codec.BuiltInKeyCodecs;
import io.aetherdb.codec.BuiltInValueCodecs;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Typed collection used by both the Swing UI and its persistence test. */
public final class NotesRepository {
    static final CollectionDefinition<UUID, String> NOTES =
            new CollectionDefinition<>(
                    CollectionId.of("7629a9f3-81a7-4346-b68f-cc273e154560"),
                    "persistent-notes",
                    BuiltInKeyCodecs.uuid(),
                    BuiltInValueCodecs.utf8String(
                            UUID.fromString("fdd89bea-06ec-4231-bff3-b26047a3b132")),
                    Set.of(
                            CollectionCapability.POINT_READ,
                            CollectionCapability.POINT_WRITE,
                            CollectionCapability.RANGE_SCAN));

    private final TypedAetherCollection<UUID, String> notes;

    public NotesRepository(TypedAetherDatabase database) {
        notes = Objects.requireNonNull(database, "database").collection(NOTES);
    }

    public Note add(String text) {
        Note note = new Note(UUID.randomUUID(), text);
        TypedWriteResult result = notes.put(note.id(), note.text());
        if (!(result instanceof TypedWriteResult.Applied)) {
            throw new IllegalStateException("note was not persisted: " + result);
        }
        return note;
    }

    public List<Note> findAll() {
        return notes.scanAll().stream().map(entry -> new Note(entry.key(), entry.value())).toList();
    }

    public void addWelcomeNotesIfEmpty() {
        if (findAll().isEmpty()) {
            add("Welcome to Aether Persistent Notes");
            add("Add a note, close this window, and run the app again.");
        }
    }
}
