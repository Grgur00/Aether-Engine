package io.aetherdb.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.engine.Aether;
import org.junit.jupiter.api.Test;

final class DatabaseWorkspaceTest {
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
}
