package io.aetherdb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.engine.Aether;
import io.aetherdb.io.BackupArchiveV1;
import io.aetherdb.io.BackupManifestObject;
import io.aetherdb.io.BackupManifestV1;
import io.aetherdb.io.BackupObjectKind;
import io.aetherdb.io.CheckpointMetadataV1;
import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.FormatOptionsV1;
import io.aetherdb.reliability.CrashPointException;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
import io.aetherdb.reliability.ScopedCrashPoint;
import io.aetherdb.reliability.TriggeringCrashPoint;
import io.aetherdb.release.CertificationArea;
import io.aetherdb.release.EvidenceStatus;
import io.aetherdb.release.ReleaseBlocker;
import io.aetherdb.release.ReleaseBlockerSeverity;
import io.aetherdb.release.ReleaseCertificationManifest;
import io.aetherdb.release.ReleaseCertificationManifestCodec;
import io.aetherdb.release.ReleaseEvidence;
import io.aetherdb.sstable.manifest.CurrentFileV1;
import io.aetherdb.sstable.manifest.ManifestCodecV1;
import io.aetherdb.sstable.manifest.ManifestEdit;
import io.aetherdb.sstable.manifest.ManifestHeaderV1;
import io.aetherdb.sstable.manifest.Version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

class AetherCliTest {
    @TempDir Path temporaryDirectory;

    @Test
    void inspectAndFullVerifyReportCurrentStorageTopology() {
        Path root = database();
        Invocation inspect = invoke("inspect", root.toString());
        assertThat(inspect.exitCode).isZero();
        assertThat(inspect.standardOut)
                .contains("Current manifest: MANIFEST-")
                .contains("L0 files/bytes: 1/")
                .contains("Health: valid");

        Invocation verify = invoke("verify", root.toString(), "--level", "FULL", "--json");
        assertThat(verify.exitCode).isZero();
        assertThat(verify.standardOut)
                .contains("\"mode\":\"verify\"")
                .contains("\"manifestRecords\":2")
                .contains("\"warnings\":0");
    }

    @Test
    void lockFailureAndUnsafeForensicOverrideHaveDistinctOutcomes() {
        Path root = database();
        try (AetherDatabase database = Aether.open(root)) {
            assertThat(database.isClosed()).isFalse();
            assertThat(invoke("inspect", root.toString()).exitCode).isEqualTo(4);
            Invocation unsafe = invoke("inspect", root.toString(), "--unsafe-no-lock");
            assertThat(unsafe.exitCode).isZero();
            assertThat(unsafe.standardError).contains("forensic read-only mode");
        }
    }

    @Test
    void incompleteManifestTailIsReportedWithoutBeingMutated() throws Exception {
        Path root = database();
        Path manifest;
        try (var files = Files.list(root)) {
            manifest =
                    files.filter(path -> path.getFileName().toString().endsWith(".aeman"))
                            .findFirst()
                            .orElseThrow();
        }
        long completeBytes = Files.size(manifest);
        Files.write(manifest, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Invocation result = invoke("inspect", root.toString());
        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut).contains("incomplete trailing bytes");
        assertThat(manifest).hasSize(completeBytes + 3);
    }

    @Test
    void checkpointHasNoWalVerifiesAndBecomesWritableOnFirstOpen() throws Exception {
        Path source = database(), checkpoint = temporaryDirectory.resolve("checkpoint");
        Invocation creation = invoke("checkpoint", source.toString(), checkpoint.toString());
        assertThat(creation.exitCode).isZero();
        assertThat(checkpoint.resolve("CHECKPOINT-METADATA")).exists().hasSize(256);
        try (var files = Files.list(checkpoint)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.endsWith(".aewal"));
        }
        assertThat(invoke("restore-verify", checkpoint.toString()).exitCode).isZero();
        try (AetherDatabase database = Aether.open(checkpoint)) {
            assertThat(new String(database.get("key".getBytes(UTF_8)).value(), UTF_8))
                    .isEqualTo("value");
            database.put("new".getBytes(UTF_8), "writable".getBytes(UTF_8));
        }
        try (AetherDatabase database = Aether.open(checkpoint)) {
            assertThat(new String(database.get("new".getBytes(UTF_8)).value(), UTF_8))
                    .isEqualTo("writable");
        }
    }

    @Test
    void checkpointCrashPointFiresAfterCopyBeforeMetadata() {
        Version version =
                Version.fromSnapshot(
                        new ManifestEdit(
                                ManifestEdit.Kind.SNAPSHOT, 1, 2, 7, 7, 0, List.of(), List.of()),
                        1);
        TriggeringCrashPoint crashPoint =
                new TriggeringCrashPoint(CrashPointIds.CHECKPOINT_AFTER_COPY_BEFORE_METADATA, 1);

        try (ScopedCrashPoint scope = CrashPointRegistry.install(crashPoint)) {
            assertThat(scope).isNotNull();
            assertThatThrownBy(
                            () ->
                                    AetherCli.hitCheckpointAfterCopyBeforeMetadata(
                                            Path.of("checkpoint"),
                                            Path.of("checkpoint.tmp-abc"),
                                            version,
                                            1,
                                            2,
                                            0))
                    .isInstanceOf(CrashPointException.class)
                    .hasMessageContaining(CrashPointIds.CHECKPOINT_AFTER_COPY_BEFORE_METADATA);
        }

        assertThat(crashPoint.hits()).isEqualTo(1);
    }

    @Test
    void backupRestorePreflightJsonPassesForVerifiedArchiveAndEmptyTarget() throws Exception {
        Path archive = temporaryDirectory.resolve("backup.aebak");
        Path target = temporaryDirectory.resolve("restore-target");
        writeBackupArchive(archive, 1, new long[] {7});

        Invocation result =
                invoke(
                        "backup-restore-preflight",
                        archive.toString(),
                        target.toString(),
                        "--available-key-epoch",
                        "7",
                        "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"backup-restore-preflight\"")
                .contains("\"passed\":true")
                .contains("\"objectCount\":2")
                .contains("\"failures\":[]");
        assertThat(target).doesNotExist();
    }

    @Test
    void backupRestorePreflightReportsBlockingFailures() throws Exception {
        Path archive = temporaryDirectory.resolve("backup.aebak");
        Path target = temporaryDirectory.resolve("restore-target");
        Files.createDirectory(target);
        Files.write(target.resolve("existing"), new byte[] {1});
        writeBackupArchive(archive, 2, new long[] {9});

        Invocation result =
                invoke("backup-restore-preflight", archive.toString(), target.toString(), "--json");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut)
                .contains("\"passed\":false")
                .contains("target directory is not empty")
                .contains("required encryption key epoch is unavailable: 9")
                .contains("unsupported backup object format version: objects/DB-IDENTITY");
    }

    @Test
    void backupRestoreWritesVerifiedArchiveObjects() throws Exception {
        Path archive = temporaryDirectory.resolve("backup.aebak");
        Path target = temporaryDirectory.resolve("restore-target");
        writeBackupArchive(archive, 1, new long[] {7});

        Invocation result =
                invoke(
                        "backup-restore",
                        archive.toString(),
                        target.toString(),
                        "--available-key-epoch",
                        "7",
                        "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"backup-restore\"")
                .contains("\"completed\":true")
                .contains("\"objectCount\":2")
                .contains("\"restoredObjects\":2");
        assertThat(Files.readString(target.resolve("DB-IDENTITY"))).isEqualTo("identity");
        assertThat(Files.readString(target.resolve("CURRENT"))).isEqualTo("current");
        assertThat(target.resolve("objects")).doesNotExist();
    }

    @Test
    void backupRestoreDoesNotWriteWhenPreflightFails() throws Exception {
        Path archive = temporaryDirectory.resolve("backup.aebak");
        Path target = temporaryDirectory.resolve("restore-target");
        writeBackupArchive(archive, 1, new long[] {7});

        Invocation result = invoke("backup-restore", archive.toString(), target.toString(), "--json");

        assertThat(result.exitCode).isEqualTo(4);
        assertThat(result.standardError).contains("preflight failed");
        assertThat(target).doesNotExist();
    }

    @Test
    void backupCreateArchivesVerifiedCheckpointObjects() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint.aebak");

        Invocation result = invoke("backup-create", checkpoint.toString(), archive.toString(), "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"backup-create\"")
                .contains("\"objectCount\":5");
        try (var input = Files.newInputStream(archive)) {
            var contents = BackupArchiveV1.read(input);
            assertThat(contents.manifest().sequenceWatermark()).isEqualTo(7);
            assertThat(contents.objects().keySet())
                    .contains(
                            "objects/DB-IDENTITY",
                            "objects/FORMAT-OPTIONS",
                            "objects/CHECKPOINT-METADATA",
                            "objects/CURRENT");
            assertThat(contents.manifest().objects())
                    .extracting(BackupManifestObject::kind)
                    .contains(BackupObjectKind.MANIFEST);
        }
    }

    @Test
    void backupCreateRejectsExistingArchive() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint.aebak");
        Files.write(archive, new byte[] {1});

        Invocation result = invoke("backup-create", checkpoint.toString(), archive.toString());

        assertThat(result.exitCode).isEqualTo(3);
        assertThat(result.standardError).contains("already exists");
        assertThat(Files.readAllBytes(archive)).containsExactly(1);
    }

    @Test
    void backupCreateAdmissionRejectsBeforeArchiveWrite() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint.aebak");

        Invocation result =
                invoke(
                        "backup-create",
                        checkpoint.toString(),
                        archive.toString(),
                        "--backup-hard-bytes",
                        "1",
                        "--json");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut)
                .contains("\"mode\":\"backup-create\"")
                .contains("\"admitted\":false")
                .contains("\"outcome\":\"RESOURCE_EXHAUSTED\"")
                .contains("BACKUP_OPERATION_BYTES");
        assertThat(archive).doesNotExist();
    }

    @Test
    void backupCreateRestoreRoundTripVerifiesCheckpointLayout() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint.aebak");
        Path restored = temporaryDirectory.resolve("restored");

        assertThat(invoke("backup-create", checkpoint.toString(), archive.toString()).exitCode)
                .isZero();
        assertThat(invoke("backup-restore", archive.toString(), restored.toString()).exitCode)
                .isZero();
        Invocation verified = invoke("restore-verify", restored.toString());

        assertThat(verified.exitCode).isZero();
        assertThat(verified.standardOut)
                .contains("Checkpoint is complete and independently restorable");
        assertThat(restored.resolve("DB-IDENTITY")).exists();
        assertThat(restored.resolve("CURRENT")).exists();
        assertThat(restored.resolve("objects")).doesNotExist();
    }

    @Test
    void encryptedBackupCreateRestoreRoundTripRequiresKeyMaterial() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint-encrypted.aebak");
        Path restored = temporaryDirectory.resolve("restored");
        String keyHex = "00".repeat(32);

        Invocation created =
                invoke(
                        "backup-create",
                        checkpoint.toString(),
                        archive.toString(),
                        "--encrypt-key-epoch",
                        "11",
                        "--encrypt-key-hex",
                        keyHex,
                        "--json");
        assertThat(created.exitCode).isZero();
        try (var input = Files.newInputStream(archive)) {
            var contents = BackupArchiveV1.read(input);
            assertThat(contents.manifest().encryptionKeyEpochs()).containsExactly(11);
            assertThat(contents.manifest().objects())
                    .allMatch(object -> object.encryptionMetadataReference().contains("key_epoch=11"));
        }

        Invocation missingKey = invoke("backup-restore", archive.toString(), restored.toString());
        assertThat(missingKey.exitCode).isEqualTo(3);
        assertThat(missingKey.standardError).contains("missing backup decryption key epoch: 11");
        assertThat(restored).doesNotExist();

        Invocation restoredResult =
                invoke(
                        "backup-restore",
                        archive.toString(),
                        restored.toString(),
                        "--key-epoch",
                        "11",
                        "--key-hex",
                        keyHex,
                        "--json");
        assertThat(restoredResult.exitCode).isZero();
        assertThat(invoke("restore-verify", restored.toString()).exitCode).isZero();
    }

    @Test
    void backupRestoreAdmissionRejectsBeforeTargetWrites() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint.aebak");
        Path restored = temporaryDirectory.resolve("restored");

        assertThat(invoke("backup-create", checkpoint.toString(), archive.toString()).exitCode)
                .isZero();
        Invocation result =
                invoke(
                        "backup-restore",
                        archive.toString(),
                        restored.toString(),
                        "--backup-hard-objects",
                        "1",
                        "--json");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut)
                .contains("\"mode\":\"backup-restore\"")
                .contains("\"admitted\":false")
                .contains("BACKUP_OBJECT_COUNT");
        assertThat(restored).doesNotExist();
    }

    @Test
    void backupRestoreDrillRestoresEncryptedArchiveAndVerifiesCheckpoint() throws Exception {
        Path checkpoint = minimalCheckpoint(temporaryDirectory.resolve("checkpoint"));
        Path archive = temporaryDirectory.resolve("checkpoint-drill.aebak");
        Path restored = temporaryDirectory.resolve("restored-drill");
        String keyHex = "11".repeat(32);

        assertThat(
                        invoke(
                                        "backup-create",
                                        checkpoint.toString(),
                                        archive.toString(),
                                        "--encrypt-key-epoch",
                                        "17",
                                        "--encrypt-key-hex",
                                        keyHex)
                                .exitCode)
                .isZero();
        Invocation drill =
                invoke(
                        "backup-restore-drill",
                        archive.toString(),
                        restored.toString(),
                        "--key-epoch",
                        "17",
                        "--key-hex",
                        keyHex,
                        "--json");

        assertThat(drill.exitCode).isZero();
        assertThat(drill.standardOut)
                .contains("\"mode\":\"backup-restore-drill\"")
                .contains("\"passed\":true")
                .contains("\"restoredObjects\":5")
                .contains("\"checkpointVerified\":true")
                .contains("\"failures\":[]");
        assertThat(invoke("restore-verify", restored.toString()).exitCode).isZero();
    }

    @Test
    void backupRestoreDrillReportsPreflightFailureWithoutWrites() throws Exception {
        Path archive = temporaryDirectory.resolve("backup.aebak");
        Path target = temporaryDirectory.resolve("restore-target");
        writeBackupArchive(archive, 1, new long[] {23});

        Invocation drill =
                invoke("backup-restore-drill", archive.toString(), target.toString(), "--json");

        assertThat(drill.exitCode).isEqualTo(2);
        assertThat(drill.standardOut)
                .contains("\"mode\":\"backup-restore-drill\"")
                .contains("\"passed\":false")
                .contains("\"checkpointVerified\":false")
                .contains("required encryption key epoch is unavailable: 23");
        assertThat(target).doesNotExist();
    }

    @Test
    void releaseCertifyReportsProductionReadyManifest() throws Exception {
        Path manifest = temporaryDirectory.resolve("release-certification.properties");
        Files.writeString(manifest, ReleaseCertificationManifestCodec.encode(releaseManifest(List.of())));

        Invocation result = invoke("release-certify", manifest.toString(), "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"release-certification\"")
                .contains("\"productionReady\":true")
                .contains("\"greenRows\":13")
                .contains("\"missingRows\":0")
                .contains("\"failures\":[]");
    }

    @Test
    void releaseCertifyBlocksOnUnresolvedReleaseRule() throws Exception {
        Path manifest = temporaryDirectory.resolve("release-certification.properties");
        Files.writeString(
                manifest,
                ReleaseCertificationManifestCodec.encode(
                        releaseManifest(
                                List.of(
                                        new ReleaseBlocker(
                                                "CERT-PLAINTEXT-RPC",
                                                "plaintext production RPC exists",
                                                ReleaseBlockerSeverity.BLOCKER,
                                                false)))));

        Invocation result = invoke("release-certify", manifest.toString());

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut)
                .contains("Release certification: FAIL")
                .contains("FAILURE: release blocker: CERT-PLAINTEXT-RPC");
    }

    @Test
    void diagnosticsBundleRedactsEnvironmentAndConfigSecrets() throws Exception {
        Path config = temporaryDirectory.resolve("aether.properties");
        Files.writeString(
                config,
                "aether.security.encryption.kek.provider=local-secret\n"
                        + "aether.rpc.max_frame_bytes=1048576\n"
                        + "custom.token=plaintext-token\n");
        Path bundle = temporaryDirectory.resolve("diagnostics.zip");

        AetherCli.DiagnosticsBundleReport report =
                AetherCli.writeDiagnosticsBundle(
                        bundle,
                        config,
                        Map.of(
                                "AETHER_SECURITY_TOKEN",
                                "env-token",
                                "AETHER_RPC_MAX_FRAME_BYTES",
                                "1048576",
                                "UNRELATED_SECRET",
                                "ignored"));

        assertThat(report.entries()).isEqualTo(3);
        String environment = zipEntry(bundle, "environment.properties");
        String properties = zipEntry(bundle, "config.properties");
        String manifest = zipEntry(bundle, "manifest.json");
        assertThat(environment)
                .contains("AETHER_SECURITY_TOKEN=REDACTED:diagnostic:")
                .contains("AETHER_RPC_MAX_FRAME_BYTES=1048576")
                .doesNotContain("env-token")
                .doesNotContain("UNRELATED_SECRET");
        assertThat(properties)
                .contains("aether.security.encryption.kek.provider=REDACTED:diagnostic:")
                .contains("custom.token=REDACTED:diagnostic:")
                .doesNotContain("local-secret")
                .doesNotContain("plaintext-token");
        assertThat(manifest).contains("\"mode\":\"diagnostics\"").contains("\"configIncluded\":true");
    }

    @Test
    void diagnosticsCommandRejectsExistingBundle() throws Exception {
        Path bundle = temporaryDirectory.resolve("diagnostics.zip");
        Files.write(bundle, new byte[] {1});

        Invocation result = invoke("diagnostics", bundle.toString(), "--json");

        assertThat(result.exitCode).isEqualTo(3);
        assertThat(result.standardError).contains("diagnostics bundle already exists");
        assertThat(Files.readAllBytes(bundle)).containsExactly(1);
    }

    @Test
    void configValidateReportsRedactedResolvedConfiguration() throws Exception {
        Path config = temporaryDirectory.resolve("aether.properties");
        Files.writeString(
                config,
                """
                aether.security.encryption.kek.provider=file://local-secret
                aether.rpc.max_frame_bytes=4096
                """);

        Invocation result =
                invoke(
                        "config-validate",
                        config.toString(),
                        "--set",
                        "aether.rpc.max_frame_bytes=8192",
                        "--json");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"config-validate\"")
                .contains("\"valid\":true")
                .contains("\"aether.rpc.max_frame_bytes\":\"8192\"")
                .contains("\"aether.security.encryption.kek.provider\":\"REDACTED\"");
        assertThat(result.standardOut).doesNotContain("local-secret");
    }

    @Test
    void configValidateReportsUnsafeProductionConfigurationAsValidationFailure() throws Exception {
        Path config = temporaryDirectory.resolve("unsafe.properties");
        Files.writeString(
                config,
                """
                aether.security.transport.tls.enabled=false
                aether.security.encryption.kek.provider=file://valid-provider
                """);

        Invocation result = invoke("config-validate", config.toString(), "--json");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.standardOut)
                .contains("\"mode\":\"config-validate\"")
                .contains("\"valid\":false")
                .contains("production requires aether.security.transport.tls.enabled");
    }

    @Test
    void commandSchemaListsMachineReadableCommandContracts() {
        Invocation result = invoke("command-schema");

        assertThat(result.exitCode).isZero();
        assertThat(result.standardOut)
                .contains("\"mode\":\"command-schema\"")
                .contains("\"command\":\"backup-create\"")
                .contains("\"jsonMode\":\"backup-create\"")
                .contains("\"jsonFields\":[\"backupId\",\"archive\",\"objectCount\",\"totalBytes\"]")
                .contains("\"command\":\"diagnostics\"")
                .contains("\"command\":\"config-validate\"")
                .contains("\"exitCodes\":[0,3,4]");
    }

    @Test
    void commandSchemaCanReportOneCommandAndRejectsUnknownCommands() {
        Invocation single = invoke("command-schema", "release-certify");

        assertThat(single.exitCode).isZero();
        assertThat(single.standardOut)
                .contains("\"command\":\"release-certify\"")
                .contains("\"jsonMode\":\"release-certification\"")
                .contains("\"productionReady\"");

        Invocation unknown = invoke("command-schema", "missing-command");
        assertThat(unknown.exitCode).isEqualTo(3);
        assertThat(unknown.standardError).contains("unknown schema command: missing-command");
    }

    @Test
    void repairTailRequiresConfirmationBacksUpAndVerifiesBothFiles() throws Exception {
        Path root = database();
        Path manifest, wal;
        try (var files = Files.list(root)) {
            List<Path> paths = files.toList();
            manifest =
                    paths.stream()
                            .filter(path -> path.getFileName().toString().endsWith(".aeman"))
                            .findFirst()
                            .orElseThrow();
            wal =
                    paths.stream()
                            .filter(path -> path.getFileName().toString().endsWith(".aewal"))
                            .findFirst()
                            .orElseThrow();
        }
        long manifestBytes = Files.size(manifest), walBytes = Files.size(wal);
        Files.write(manifest, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Files.write(wal, new byte[] {4, 5, 6, 7}, java.nio.file.StandardOpenOption.APPEND);
        assertThat(invoke("repair-tail", root.toString()).exitCode).isEqualTo(2);
        assertThat(manifest).hasSize(manifestBytes + 3);
        assertThat(wal).hasSize(walBytes + 4);
        Invocation repaired = invoke("repair-tail", root.toString(), "--yes");
        assertThat(repaired.exitCode).isZero();
        assertThat(manifest).hasSize(manifestBytes);
        assertThat(wal).hasSize(walBytes);
        try (var files = Files.list(root)) {
            assertThat(
                            files.map(path -> path.getFileName().toString())
                                    .filter(name -> name.contains(".pre-repair-"))
                                    .count())
                    .isEqualTo(2);
        }
        assertThat(invoke("verify", root.toString()).exitCode).isZero();
    }

    @Test
    void rebuildCurrentRequiresUniqueValidatedManifestAndConfirmation() throws Exception {
        Path root = database(), current = root.resolve("CURRENT");
        Files.delete(current);
        Invocation plan = invoke("rebuild-current", root.toString());
        assertThat(plan.exitCode).isEqualTo(2);
        assertThat(current).doesNotExist();
        Invocation applied = invoke("rebuild-current", root.toString(), "--yes");
        assertThat(applied.exitCode).isZero();
        assertThat(current).exists().hasSize(128);
        assertThat(invoke("verify", root.toString()).exitCode).isZero();
    }

    @Test
    void salvageCreatesNewIdentityAndSupportsLatestAndHistoryModes() throws Exception {
        Path source = temporaryDirectory.resolve("salvage-source");
        UUID sourceId;
        try (AetherDatabase database = Aether.open(source)) {
            database.put("key".getBytes(UTF_8), "old".getBytes(UTF_8));
            database.put("key".getBytes(UTF_8), "new".getBytes(UTF_8));
        }
        sourceId =
                io.aetherdb.io.DatabaseIdentityV1.decode(
                                Files.readAllBytes(source.resolve("DB-IDENTITY")))
                        .databaseId();
        writeSalvageWal(source, sourceId, 99, 100, "wal-only", "from-wal");
        Path latest = temporaryDirectory.resolve("salvage-latest");
        assertThat(invoke("salvage", source.toString(), latest.toString()).exitCode).isZero();
        UUID latestId =
                io.aetherdb.io.DatabaseIdentityV1.decode(
                                Files.readAllBytes(latest.resolve("DB-IDENTITY")))
                        .databaseId();
        assertThat(latestId).isNotEqualTo(sourceId);
        try (AetherDatabase database = Aether.open(latest)) {
            assertThat(new String(database.get("key".getBytes(UTF_8)).value(), UTF_8))
                    .isEqualTo("new");
            assertThat(new String(database.get("wal-only".getBytes(UTF_8)).value(), UTF_8))
                    .isEqualTo("from-wal");
        }
        Path history = temporaryDirectory.resolve("salvage-history");
        assertThat(
                        invoke(
                                        "salvage",
                                        source.toString(),
                                        history.toString(),
                                        "--mode",
                                        "PRESERVE_VALID_HISTORY")
                                .exitCode)
                .isZero();
        assertThat(history.resolve("SALVAGE-REPORT.json"))
                .content()
                .contains("PRESERVE_VALID_HISTORY");
    }

    @Test
    void salvageSkipsCorruptTableAndPublishesExplicitDataLossReport() throws Exception {
        Path source = database(), destination = temporaryDirectory.resolve("damaged-salvage");
        Path table;
        try (var files = Files.list(source)) {
            table =
                    files.filter(path -> path.getFileName().toString().endsWith(".aess"))
                            .findFirst()
                            .orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table);
        bytes[4096] ^= 1;
        Files.write(table, bytes);
        Invocation salvage = invoke("salvage", source.toString(), destination.toString());
        assertThat(salvage.exitCode).isEqualTo(2);
        assertThat(destination.resolve("SALVAGE-REPORT.json"))
                .content()
                .contains("\"possibleDataLoss\":true");
        try (AetherDatabase database = Aether.open(destination)) {
            assertThat(database.get("key".getBytes(UTF_8)).isFound()).isFalse();
        }
    }

    private Path database() {
        return database(temporaryDirectory);
    }

    private static void writeBackupArchive(Path archive, int requiredFormatVersion, long[] keyEpochs)
            throws Exception {
        Map<String, byte[]> objects =
                Map.of(
                        "objects/DB-IDENTITY",
                        "identity".getBytes(UTF_8),
                        "objects/CURRENT",
                        "current".getBytes(UTF_8));
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        UUID.fromString("11111111-2222-3333-4444-555555555555"),
                        42,
                        "0.2.0-dev",
                        1,
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        null,
                        -1,
                        -1,
                        100,
                        BackupManifestV1.HASH_SHA256,
                        List.of(
                                backupObject(
                                        "objects/DB-IDENTITY",
                                        BackupObjectKind.DATABASE_IDENTITY,
                                        objects,
                                        requiredFormatVersion),
                                backupObject(
                                        "objects/CURRENT",
                                        BackupObjectKind.CURRENT,
                                        objects,
                                        1)),
                        keyEpochs,
                        fingerprint());
        try (var output = Files.newOutputStream(archive)) {
            BackupArchiveV1.write(output, manifest, objects);
        }
    }

    private static Path minimalCheckpoint(Path checkpoint) throws Exception {
        Files.createDirectories(checkpoint);
        UUID databaseId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        long created = 42;
        DatabaseIdentityV1 identity = new DatabaseIdentityV1(databaseId, created, 0, 2);
        FormatOptionsV1 options = new FormatOptionsV1(databaseId, created);
        Files.write(checkpoint.resolve("DB-IDENTITY"), identity.encode());
        Files.write(checkpoint.resolve("FORMAT-OPTIONS"), options.encode());
        ManifestEdit snapshot =
                new ManifestEdit(
                        ManifestEdit.Kind.SNAPSHOT,
                        1,
                        2,
                        7,
                        7,
                        0,
                        List.of(),
                        List.of());
        String manifestName = CurrentFileV1.manifestName(1);
        byte[] header = new ManifestHeaderV1(databaseId, 1, created, 2, 7).encodeRegion();
        byte[] record = ManifestCodecV1.encodeRecord(snapshot);
        byte[] manifest = new byte[header.length + record.length];
        System.arraycopy(header, 0, manifest, 0, header.length);
        System.arraycopy(record, 0, manifest, header.length, record.length);
        Files.write(checkpoint.resolve(manifestName), manifest);
        Files.write(checkpoint.resolve("CURRENT"), CurrentFileV1.encode(databaseId, 1));
        CheckpointMetadataV1 metadata =
                new CheckpointMetadataV1(
                        databaseId,
                        7,
                        created,
                        1,
                        1,
                        1,
                        0,
                        0,
                        options.compatibilityFingerprint());
        Files.write(checkpoint.resolve("CHECKPOINT-METADATA"), metadata.encode());
        return checkpoint;
    }

    private static BackupManifestObject backupObject(
            String path, BackupObjectKind kind, Map<String, byte[]> objects, int requiredFormatVersion)
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

    private static ReleaseCertificationManifest releaseManifest(List<ReleaseBlocker> blockers) {
        List<ReleaseEvidence> evidence = new ArrayList<>();
        for (CertificationArea area : CertificationArea.values()) {
            evidence.add(
                    new ReleaseEvidence(
                            area,
                            EvidenceStatus.GREEN,
                            area.name() + " evidence passed",
                            List.of(URI.create("file:///" + area.name() + ".json")),
                            ""));
        }
        return ReleaseCertificationManifest.of(
                "0.2.0-rc.1",
                "0123456789abcdef0123456789abcdef01234567",
                Instant.parse("2026-08-19T12:00:00Z"),
                evidence,
                blockers,
                List.of(URI.create("file:///release-certification.json")));
    }

    private static String zipEntry(Path archive, String name) throws Exception {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(input.readAllBytes(), UTF_8);
                }
            }
        }
        throw new AssertionError("zip entry missing: " + name);
    }

    private static Path database(Path parent) {
        Path root = parent.resolve("database");
        try (AetherDatabase database = Aether.open(root)) {
            database.put("key".getBytes(UTF_8), "value".getBytes(UTF_8));
        }
        return root;
    }

    private static void writeSalvageWal(
            Path root,
            UUID databaseId,
            long segment,
            long sequence,
            String keyText,
            String valueText)
            throws Exception {
        byte[] key = keyText.getBytes(UTF_8), value = valueText.getBytes(UTF_8);
        byte[] logical =
                new byte
                        [io.aetherdb.wal.format.WalFormatV1.GROUP_HEADER_BYTES
                                + io.aetherdb.wal.format.WalFormatV1.OPERATION_HEADER_BYTES
                                + key.length
                                + value.length];
        java.nio.ByteBuffer bytes =
                java.nio.ByteBuffer.wrap(logical).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put("AETHGRP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) io.aetherdb.wal.format.WalFormatV1.GROUP_HEADER_BYTES)
                .putInt(logical.length)
                .putLong(sequence)
                .putLong(sequence)
                .putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .put((byte) 1)
                .put(new byte[3])
                .putInt(key.length)
                .putInt(value.length)
                .put(key)
                .put(value);
        bytes.putInt(44, io.aetherdb.format.checksum.MaskedCrc32c.masked(logical, 0, 44));
        byte[] header =
                new io.aetherdb.wal.format.WalSegmentHeader(
                                databaseId, segment, 0, sequence, System.currentTimeMillis())
                        .encodeBlock();
        byte[] fragments =
                io.aetherdb.wal.format.WalFragmentCodec.fragment(
                        logical, io.aetherdb.wal.format.WalFormatV1.HEADER_BLOCK_BYTES, 1);
        byte[] file = new byte[header.length + fragments.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(fragments, 0, file, header.length, fragments.length);
        Files.write(root.resolve(io.aetherdb.wal.format.WalFormatV1.fileName(segment)), file);
    }

    private static Invocation invoke(String... arguments) {
        PrintStream originalOut = System.out, originalError = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream(),
                error = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, UTF_8));
            System.setErr(new PrintStream(error, true, UTF_8));
            return new Invocation(
                    AetherCli.run(arguments), output.toString(UTF_8), error.toString(UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalError);
        }
    }

    private record Invocation(int exitCode, String standardOut, String standardError) {}
}
