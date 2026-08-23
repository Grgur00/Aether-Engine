package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class BackupRestorePreflightTest {
    private static final UUID BACKUP_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DATABASE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID CLUSTER_ID =
            UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @TempDir Path temporaryDirectory;

    @Test
    void singleNodePreflightPassesForEmptyTargetAndAvailableKeys() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        BackupRestorePreflightReport report =
                BackupRestorePreflight.check(
                        contents(singleNodeManifest(1, new long[] {7})),
                        target,
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                false,
                                1,
                                Set.of(7L),
                                null,
                                null));

        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.objectCount()).isEqualTo(2);
        assertThat(report.totalBytes()).isEqualTo(15);
        assertThat(target).doesNotExist();
    }

    @Test
    void preflightRejectsNonEmptyTargetUnlessExplicitlyAllowed() throws Exception {
        Path target = temporaryDirectory.resolve("restore");
        Files.createDirectories(target);
        Files.write(target.resolve("existing"), new byte[] {1});

        BackupRestorePreflightReport rejected =
                BackupRestorePreflight.check(
                        contents(singleNodeManifest(1, new long[0])),
                        target,
                        options(BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID));
        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.failures()).contains("target directory is not empty");

        BackupRestorePreflightReport allowed =
                BackupRestorePreflight.check(
                        contents(singleNodeManifest(1, new long[0])),
                        target,
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID,
                                true,
                                1,
                                Set.of(),
                                null,
                                null));
        assertThat(allowed.passed()).isTrue();
    }

    @Test
    void preflightRejectsMissingKeysAndUnsupportedObjectFormats() throws Exception {
        BackupRestorePreflightReport report =
                BackupRestorePreflight.check(
                        contents(singleNodeManifest(2, new long[] {7})),
                        temporaryDirectory.resolve("restore"),
                        options(BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID));

        assertThat(report.passed()).isFalse();
        assertThat(report.failures())
                .contains(
                        "required encryption key epoch is unavailable: 7",
                        "unsupported backup object format version: objects/DB-IDENTITY");
    }

    @Test
    void preflightEnforcesRestoreModeIdentityRules() throws Exception {
        BackupRestorePreflightReport preserveWrongDatabase =
                BackupRestorePreflight.check(
                        contents(singleNodeManifest(1, new long[0])),
                        temporaryDirectory.resolve("preserve"),
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.SINGLE_NODE_PRESERVE_DATABASE_ID,
                                false,
                                1,
                                Set.of(),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                null));
        assertThat(preserveWrongDatabase.passed()).isFalse();
        assertThat(preserveWrongDatabase.failures())
                .contains("target database identity does not match backup");

        BackupRestorePreflightReport replacementWrongCluster =
                BackupRestorePreflight.check(
                        contents(clusterManifest(1)),
                        temporaryDirectory.resolve("member"),
                        new BackupRestorePreflightOptions(
                                BackupRestoreMode.CLUSTER_MEMBER_REPLACEMENT,
                                false,
                                1,
                                Set.of(),
                                null,
                                UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertThat(replacementWrongCluster.passed()).isFalse();
        assertThat(replacementWrongCluster.failures())
                .contains("target cluster identity does not match backup");
    }

    private static BackupRestorePreflightOptions options(BackupRestoreMode mode) {
        return new BackupRestorePreflightOptions(mode, false, 1, Set.of(), null, null);
    }

    private static BackupArchiveContents contents(BackupManifestV1 manifest) {
        return new BackupArchiveContents(manifest, objects());
    }

    private static BackupManifestV1 singleNodeManifest(int requiredFormatVersion, long[] epochs)
            throws Exception {
        return manifest(null, requiredFormatVersion, epochs);
    }

    private static BackupManifestV1 clusterManifest(int requiredFormatVersion) throws Exception {
        return manifest(CLUSTER_ID, requiredFormatVersion, new long[0]);
    }

    private static BackupManifestV1 manifest(
            UUID clusterId, int requiredFormatVersion, long[] epochs) throws Exception {
        Map<String, byte[]> objects = objects();
        return new BackupManifestV1(
                BACKUP_ID,
                42,
                "0.2.0-dev",
                1,
                DATABASE_ID,
                clusterId,
                clusterId == null ? -1 : 10,
                clusterId == null ? -1 : 2,
                100,
                BackupManifestV1.HASH_SHA256,
                List.of(
                        object(
                                "objects/DB-IDENTITY",
                                BackupObjectKind.DATABASE_IDENTITY,
                                objects,
                                requiredFormatVersion),
                        object("objects/CURRENT", BackupObjectKind.CURRENT, objects, 1)),
                epochs,
                fingerprint());
    }

    private static Map<String, byte[]> objects() {
        return Map.of(
                "objects/DB-IDENTITY", "identity".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "objects/CURRENT", "current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static BackupManifestObject object(
            String path, BackupObjectKind kind, Map<String, byte[]> objects, int requiredFormatVersion)
            throws Exception {
        byte[] bytes = objects.get(path);
        return new BackupManifestObject(path, kind, bytes.length, sha256(bytes), "", requiredFormatVersion);
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
