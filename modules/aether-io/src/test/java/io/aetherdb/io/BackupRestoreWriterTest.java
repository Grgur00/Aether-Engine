package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class BackupRestoreWriterTest {
    private static final UUID BACKUP_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DATABASE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @TempDir Path temporaryDirectory;

    @Test
    void restoreWritesVerifiedObjectsAfterPreflightPasses() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        BackupRestoreResult result =
                BackupRestoreWriter.restore(
                        contents(objects()),
                        target,
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                false,
                                1,
                                Set.of(),
                                null,
                                null));

        assertThat(result.targetDirectory()).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(result.restoredObjects())
                .containsExactly("objects/DB-IDENTITY", "objects/CURRENT");
        assertThat(Files.readString(target.resolve("objects/DB-IDENTITY"))).isEqualTo("identity");
        assertThat(Files.readString(target.resolve("objects/CURRENT"))).isEqualTo("current");
    }

    @Test
    void restoreCheckpointLayoutWritesEngineRootFiles() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        BackupRestoreResult result =
                BackupRestoreWriter.restoreCheckpointLayout(
                        contents(objects()),
                        target,
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                false,
                                1,
                                Set.of(),
                                null,
                                null));

        assertThat(result.restoredObjects()).containsExactly("DB-IDENTITY", "CURRENT");
        assertThat(Files.readString(target.resolve("DB-IDENTITY"))).isEqualTo("identity");
        assertThat(Files.readString(target.resolve("CURRENT"))).isEqualTo("current");
        assertThat(target.resolve("objects")).doesNotExist();
    }

    @Test
    void restoreCheckpointLayoutRejectsUnexpectedObjectKindOrName() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        Map<String, byte[]> objects =
                Map.of("objects/CURRENT", "current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        BACKUP_ID,
                        42,
                        "0.2.0-dev",
                        1,
                        DATABASE_ID,
                        null,
                        -1,
                        -1,
                        100,
                        BackupManifestV1.HASH_SHA256,
                        List.of(
                                new BackupManifestObject(
                                        "objects/CURRENT",
                                        BackupObjectKind.SSTABLE,
                                        objects.get("objects/CURRENT").length,
                                        sha256(objects.get("objects/CURRENT")),
                                        "",
                                        1)),
                        new long[0],
                        fingerprint());

        assertThatThrownBy(
                        () ->
                                BackupRestoreWriter.restoreCheckpointLayout(
                                        new BackupArchiveContents(manifest, objects),
                                        target,
                                        new BackupRestorePreflightOptions(
                                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                                false,
                                                1,
                                                Set.of(),
                                                null,
                                                null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported checkpoint backup object");
        assertThat(target.resolve("CURRENT")).doesNotExist();
    }

    @Test
    void restoreDoesNotWriteWhenPreflightFails() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        BackupArchiveContents contents = contents(objects());

        assertThatThrownBy(
                        () ->
                                BackupRestoreWriter.restore(
                                        contents,
                                        target,
                                        new BackupRestorePreflightOptions(
                                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                                false,
                                                0,
                                                Set.of(),
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class);

        BackupManifestV1 manifestWithKey =
                manifest(objects(), 1, new long[] {9});
        assertThatThrownBy(
                        () ->
                                BackupRestoreWriter.restore(
                                        new BackupArchiveContents(manifestWithKey, objects()),
                                        target,
                                        new BackupRestorePreflightOptions(
                                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                                false,
                                                1,
                                                Set.of(),
                                                null,
                                                null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("preflight failed");
        assertThat(target).doesNotExist();
    }

    @Test
    void restoreCleansObjectsWrittenBeforeCollision() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        Files.createDirectories(target.resolve("objects"));
        Files.writeString(target.resolve("objects/CURRENT"), "existing");

        assertThatThrownBy(
                        () ->
                                BackupRestoreWriter.restore(
                                        contents(objects()),
                                        target,
                                        new BackupRestorePreflightOptions(
                                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                                true,
                                                1,
                                                Set.of(),
                                                null,
                                                null)))
                .isInstanceOf(IOException.class);

        assertThat(target.resolve("objects/DB-IDENTITY")).doesNotExist();
        assertThat(Files.readString(target.resolve("objects/CURRENT"))).isEqualTo("existing");
    }

    private static BackupArchiveContents contents(Map<String, byte[]> objects) throws Exception {
        return new BackupArchiveContents(manifest(objects, 1, new long[0]), objects);
    }

    private static BackupManifestV1 manifest(
            Map<String, byte[]> objects, int requiredFormatVersion, long[] keyEpochs)
            throws Exception {
        return new BackupManifestV1(
                BACKUP_ID,
                42,
                "0.2.0-dev",
                1,
                DATABASE_ID,
                null,
                -1,
                -1,
                100,
                BackupManifestV1.HASH_SHA256,
                List.of(
                        object(
                                "objects/DB-IDENTITY",
                                BackupObjectKind.DATABASE_IDENTITY,
                                objects,
                                requiredFormatVersion),
                        object("objects/CURRENT", BackupObjectKind.CURRENT, objects, 1)),
                keyEpochs,
                fingerprint());
    }

    private static Map<String, byte[]> objects() {
        return Map.of(
                "objects/DB-IDENTITY", "identity".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "objects/CURRENT", "current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static BackupManifestObject object(
            String path,
            BackupObjectKind kind,
            Map<String, byte[]> objects,
            int requiredFormatVersion)
            throws Exception {
        byte[] bytes = objects.get(path);
        return new BackupManifestObject(
                path, kind, bytes.length, sha256(bytes), "", requiredFormatVersion);
    }

    private static byte[] fingerprint() {
        byte[] fingerprint = new byte[32];
        for (int index = 0; index < fingerprint.length; index++) fingerprint[index] = (byte) index;
        return fingerprint;
    }

    private static byte[] sha256(byte[] contents) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(contents);
    }
}
