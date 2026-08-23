package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BackupArchiveV1Test {
    private static final UUID BACKUP_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DATABASE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void archiveRoundTripsManifestAndVerifiedObjects() throws Exception {
        Map<String, byte[]> objects =
                Map.of(
                        "objects/DB-IDENTITY", "identity".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "objects/CURRENT", "current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BackupManifestV1 manifest = manifest(objects);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        BackupArchiveV1.write(encoded, manifest, objects);
        BackupArchiveContents decoded =
                BackupArchiveV1.read(new ByteArrayInputStream(encoded.toByteArray()));

        assertThat(decoded.manifest().backupId()).isEqualTo(BACKUP_ID);
        assertThat(decoded.objectBytes("objects/DB-IDENTITY")).isEqualTo(objects.get("objects/DB-IDENTITY"));
        byte[] mutable = decoded.objectBytes("objects/CURRENT");
        mutable[0] ^= 1;
        assertThat(decoded.objectBytes("objects/CURRENT")).isEqualTo(objects.get("objects/CURRENT"));
    }

    @Test
    void writerRejectsMissingExtraOrCorruptObjects() throws Exception {
        Map<String, byte[]> objects = baseObjects();
        BackupManifestV1 manifest = manifest(objects);

        Map<String, byte[]> missing = new HashMap<>(objects);
        missing.remove("objects/CURRENT");
        assertThatThrownBy(() -> BackupArchiveV1.write(new ByteArrayOutputStream(), manifest, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");

        Map<String, byte[]> extra = new HashMap<>(objects);
        extra.put("objects/EXTRA", new byte[] {1});
        assertThatThrownBy(() -> BackupArchiveV1.write(new ByteArrayOutputStream(), manifest, extra))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory");

        Map<String, byte[]> corrupt = new HashMap<>(objects);
        corrupt.put("objects/CURRENT", "wrong!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> BackupArchiveV1.write(new ByteArrayOutputStream(), manifest, corrupt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void readerRejectsObjectTamperingAfterArchiveCreation() throws Exception {
        Map<String, byte[]> objects = baseObjects();
        BackupManifestV1 manifest = manifest(objects);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(encoded)) {
            putEntry(archive, BackupArchiveV1.MANIFEST_ENTRY, manifest.encode());
            putEntry(archive, "objects/DB-IDENTITY", objects.get("objects/DB-IDENTITY"));
            putEntry(archive, "objects/CURRENT", "wrong!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> BackupArchiveV1.read(new ByteArrayInputStream(encoded.toByteArray())))
                .isInstanceOf(IOException.class);
    }

    @Test
    void corruptionMutatorDrivesArchiveTailAndObjectChecksumFailures() throws Exception {
        Map<String, byte[]> objects = baseObjects();
        BackupManifestV1 manifest = manifest(objects);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        BackupArchiveV1.write(encoded, manifest, objects);
        byte[] tornArchive =
                CorruptionMutator.apply(
                        encoded.toByteArray(), CorruptionPlan.truncate(encoded.size() / 2));
        assertThatThrownBy(() -> BackupArchiveV1.read(new ByteArrayInputStream(tornArchive)))
                .isInstanceOf(IOException.class);

        byte[] corruptCurrent =
                CorruptionMutator.apply(objects.get("objects/CURRENT"), CorruptionPlan.flipBit(0, 0));
        ByteArrayOutputStream corruptArchive = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(corruptArchive)) {
            putEntry(archive, BackupArchiveV1.MANIFEST_ENTRY, manifest.encode());
            putEntry(archive, "objects/DB-IDENTITY", objects.get("objects/DB-IDENTITY"));
            putEntry(archive, "objects/CURRENT", corruptCurrent);
        }
        assertThatThrownBy(() -> BackupArchiveV1.read(new ByteArrayInputStream(corruptArchive.toByteArray())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
    }

    private static Map<String, byte[]> baseObjects() {
        return Map.of(
                "objects/DB-IDENTITY", "identity".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "objects/CURRENT", "current".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static BackupManifestV1 manifest(Map<String, byte[]> objects) throws Exception {
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
                        object("objects/DB-IDENTITY", BackupObjectKind.DATABASE_IDENTITY, objects),
                        object("objects/CURRENT", BackupObjectKind.CURRENT, objects)),
                new long[0],
                fingerprint());
    }

    private static BackupManifestObject object(
            String path, BackupObjectKind kind, Map<String, byte[]> objects) throws Exception {
        byte[] bytes = objects.get(path);
        return new BackupManifestObject(path, kind, bytes.length, sha256(bytes), "", 1);
    }

    private static byte[] fingerprint() {
        byte[] fingerprint = new byte[32];
        for (int index = 0; index < fingerprint.length; index++) fingerprint[index] = (byte) index;
        return fingerprint;
    }

    private static byte[] sha256(byte[] contents) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(contents);
    }

    private static void putEntry(ZipOutputStream archive, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        archive.putNextEntry(entry);
        archive.write(bytes);
        archive.closeEntry();
    }
}
