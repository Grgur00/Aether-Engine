package io.aetherdb.examples.notes;

import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

import java.util.UUID;

/** One immutable note displayed by the sample application. */
@AetherRecord(version = 1)
public record Note(UUID id, @AetherMaxLength(4096) String text) {
    public Note {
        if (id == null) throw new IllegalArgumentException("note ID is required");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("note text must not be blank");
        text = text.strip();
    }

    @Override
    public String toString() {
        return text;
    }
}
