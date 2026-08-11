package io.aetherdb.examples.notes;

import io.aetherdb.api.typed.TypedAetherCollection;
import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.api.typed.TypedKeyValue;
import io.aetherdb.api.typed.TypedWriteResult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed collection used by both the Swing UI and its persistence test. */
public final class NotesRepository {
    private final TypedAetherCollection<UUID, Note> notes;

    public NotesRepository(TypedAetherDatabase database) {
        notes =
                Objects.requireNonNull(database, "database")
                        .defineCollection("persistent-notes", UUID.class, Note.class);
    }

    public Note add(String text) {
        Note note = new Note(UUID.randomUUID(), text);
        TypedWriteResult result = notes.put(note.id(), note);
        if (!(result instanceof TypedWriteResult.Applied)) {
            throw new IllegalStateException("note was not persisted: " + result);
        }
        return note;
    }

    public List<Note> findAll() {
        return notes.scanAll().stream().map(TypedKeyValue::value).toList();
    }

    public void addWelcomeNotesIfEmpty() {
        if (findAll().isEmpty()) {
            add("Welcome to Aether Persistent Notes");
            add("Add a note, close this window, and run the app again.");
        }
    }
}
