package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

class BackupManifestV1Test {
    private static final UUID BACKUP_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DATABASE_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID CLUSTER_ID =
            UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void manifestRoundTripsWithObjectsAndKeyEpochs() throws Exception {
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        BACKUP_ID,
                        42,
                        "0.2.0-dev",
                        1,
                        DATABASE_ID,
                        CLUSTER_ID,
                        99,
                        7,
                        123,
                        BackupManifestV1.HASH_SHA256,
                        List.of(
                                object(
                                        "objects/DB-IDENTITY",
                                        BackupObjectKind.DATABASE_IDENTITY,
                                        128,
                                        "identity"),
                                object("objects/00000000000000000003.aess", BackupObjectKind.SSTABLE, 4096, "table")),
                        new long[] {3, 4},
                        fingerprint());

        byte[] encoded = manifest.encode();
        BackupManifestV1 decoded = BackupManifestV1.decode(encoded);

        assertThat(decoded.backupId()).isEqualTo(BACKUP_ID);
        assertThat(decoded.databaseId()).isEqualTo(DATABASE_ID);
        assertThat(decoded.clusterId()).isEqualTo(CLUSTER_ID);
        assertThat(decoded.objectCount()).isEqualTo(2);
        assertThat(decoded.totalBytes()).isEqualTo(4224);
        assertThat(decoded.encryptionKeyEpochs()).containsExactly(3, 4);
        assertThat(decoded.objects().get(1).path()).isEqualTo("objects/00000000000000000003.aess");
        assertThat(decoded.compatibilityFingerprint()).isEqualTo(fingerprint());
    }

    @Test
    void manifestRejectsCorruptionAndDuplicateOrUnsafeObjects() throws Exception {
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
                        123,
                        BackupManifestV1.HASH_SHA256,
                        List.of(object("objects/CURRENT", BackupObjectKind.CURRENT, 128, "current")),
                        new long[0],
                        fingerprint());
        byte[] encoded =
                CorruptionMutator.apply(
                        manifest.encode(), CorruptionPlan.flipBit(BackupManifestV1.HEADER_BYTES + 8, 0));
        assertThatThrownBy(() -> BackupManifestV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        byte[] tornManifest =
                CorruptionMutator.apply(manifest.encode(), CorruptionPlan.truncate(BackupManifestV1.HEADER_BYTES));
        assertThatThrownBy(() -> BackupManifestV1.decode(tornManifest))
                .isInstanceOf(IllegalArgumentException.class);

        BackupManifestObject object = object("objects/CURRENT", BackupObjectKind.CURRENT, 128, "current");
        assertThatThrownBy(
                        () ->
                                new BackupManifestV1(
                                        BACKUP_ID,
                                        42,
                                        "0.2.0-dev",
                                        1,
                                        DATABASE_ID,
                                        null,
                                        -1,
                                        -1,
                                        123,
                                        BackupManifestV1.HASH_SHA256,
                                        List.of(object, object),
                                        new long[0],
                                        fingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(
                        () ->
                                new BackupManifestObject(
                                        "../CURRENT",
                                        BackupObjectKind.CURRENT,
                                        128,
                                        sha256("current"),
                                        "",
                                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void manifestDefensivelyCopiesMutableArrays() throws Exception {
        byte[] checksum = sha256("identity");
        byte[] fingerprint = fingerprint();
        long[] epochs = {7};
        BackupManifestObject object =
                new BackupManifestObject(
                        "objects/DB-IDENTITY",
                        BackupObjectKind.DATABASE_IDENTITY,
                        128,
                        checksum,
                        "",
                        1);
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
                        123,
                        BackupManifestV1.HASH_SHA256,
                        List.of(object),
                        epochs,
                        fingerprint);

        checksum[0] ^= 1;
        fingerprint[0] ^= 1;
        epochs[0] = 99;

        assertThat(object.checksum()).isEqualTo(sha256("identity"));
        assertThat(manifest.compatibilityFingerprint()).isEqualTo(fingerprint());
        assertThat(manifest.encryptionKeyEpochs()).containsExactly(7);
    }

    private static BackupManifestObject object(
            String path, BackupObjectKind kind, long length, String contents) throws Exception {
        return new BackupManifestObject(path, kind, length, sha256(contents), "", 1);
    }

    private static byte[] fingerprint() {
        byte[] fingerprint = new byte[32];
        for (int index = 0; index < fingerprint.length; index++) fingerprint[index] = (byte) index;
        return fingerprint;
    }

    private static byte[] sha256(String contents) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
