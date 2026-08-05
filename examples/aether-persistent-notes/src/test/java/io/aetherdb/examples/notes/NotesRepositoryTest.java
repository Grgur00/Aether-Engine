package io.aetherdb.examples.notes;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.embedded.typed.AetherEmbedded;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotesRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void notesAddedByTheUiRepositorySurviveApplicationStyleReopen() {
        Path directory = temp.resolve("notes");

        try (var database = AetherEmbedded.open(directory)) {
            new NotesRepository(database).add("Remember me");
        }

        try (var database = AetherEmbedded.open(directory)) {
            assertThat(new NotesRepository(database).findAll())
                    .extracting(Note::text)
                    .containsExactly("Remember me");
        }
    }
}
