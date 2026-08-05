package io.aetherdb.examples.notes;

import java.util.UUID;

/** One immutable note displayed by the sample application. */
public record Note(UUID id, String text) {
    public Note {
        if (id == null) throw new IllegalArgumentException("note ID is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("note text must not be blank");
        text = text.strip();
    }

    @Override public String toString() { return text; }
}
