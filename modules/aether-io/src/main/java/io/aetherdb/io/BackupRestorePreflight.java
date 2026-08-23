package io.aetherdb.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Restore preflight checks for a verified portable backup archive. */
public final class BackupRestorePreflight {
    private BackupRestorePreflight() {}

    /**
     * Validates restore safety before any restore files are written.
     *
     * @param contents previously decoded and object-verified archive contents
     * @param targetDirectory target directory candidate
     * @param options restore operator policy and binary capabilities
     * @return preflight report
     */
    public static BackupRestorePreflightReport check(
            BackupArchiveContents contents,
            Path targetDirectory,
            BackupRestorePreflightOptions options)
            throws IOException {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Objects.requireNonNull(options, "options");
        BackupManifestV1 manifest = contents.manifest();
        List<String> failures = new ArrayList<>();
        checkTarget(targetDirectory.toAbsolutePath().normalize(), options, failures);
        checkKeys(manifest, options, failures);
        checkFormatVersions(manifest, options, failures);
        checkRestoreMode(manifest, options, failures);
        return new BackupRestorePreflightReport(
                failures.isEmpty(), failures, manifest.objectCount(), manifest.totalBytes());
    }

    private static void checkTarget(
            Path target, BackupRestorePreflightOptions options, List<String> failures)
            throws IOException {
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                failures.add("target directory is not a directory");
                return;
            }
            try (var entries = Files.list(target)) {
                if (!options.allowNonEmptyTarget() && entries.findAny().isPresent())
                    failures.add("target directory is not empty");
            }
        } else {
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent))
                failures.add("target directory parent does not exist");
        }
    }

    private static void checkKeys(
            BackupManifestV1 manifest,
            BackupRestorePreflightOptions options,
            List<String> failures) {
        for (long epoch : manifest.encryptionKeyEpochs())
            if (!options.availableEncryptionKeyEpochs().contains(epoch))
                failures.add("required encryption key epoch is unavailable: " + epoch);
    }

    private static void checkFormatVersions(
            BackupManifestV1 manifest,
            BackupRestorePreflightOptions options,
            List<String> failures) {
        for (BackupManifestObject object : manifest.objects())
            if (object.requiredFormatVersion() > options.supportedFormatVersion())
                failures.add("unsupported backup object format version: " + object.path());
    }

    private static void checkRestoreMode(
            BackupManifestV1 manifest,
            BackupRestorePreflightOptions options,
            List<String> failures) {
        UUID clusterId = manifest.clusterId();
        switch (options.mode()) {
            case SINGLE_NODE_PRESERVE_DATABASE_ID -> {
                if (clusterId != null)
                    failures.add("cluster backup cannot be restored as a single-node database");
                if (!manifest.databaseId().equals(options.existingDatabaseId()))
                    failures.add("target database identity does not match backup");
            }
            case SINGLE_NODE_NEW_DATABASE_ID -> {
                if (clusterId != null)
                    failures.add("cluster backup cannot be restored as a single-node database");
            }
            case CLUSTER_DISASTER_NEW_CLUSTER -> {
                if (clusterId == null) failures.add("single-node backup cannot seed cluster disaster restore");
            }
            case CLUSTER_MEMBER_REPLACEMENT -> {
                if (clusterId == null) {
                    failures.add("single-node backup cannot replace a cluster member");
                } else if (!clusterId.equals(options.existingClusterId())) {
                    failures.add("target cluster identity does not match backup");
                }
            }
        }
    }
}
