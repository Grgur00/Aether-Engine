package io.aetherdb.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.io.BackupManifestObject;
import io.aetherdb.io.BackupObjectKind;
import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

class BackupObjectAeadTest {
    private static final UUID BACKUP_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final byte[] KEY = new byte[32];

    @Test
    void encryptedBackupObjectRoundTripsAndCarriesManifestReference() throws Exception {
        byte[] plaintext = "identity".getBytes(StandardCharsets.UTF_8);
        EncryptedBackupObject encrypted =
                BackupObjectAead.encrypt(BACKUP_ID, "objects/DB-IDENTITY", KEY, 7, plaintext);

        assertThat(encrypted.encryptionMetadataReference())
                .isEqualTo("aether-backup-object-v1:alg=AES-256-GCM:key_epoch=7");
        assertThat(
                        BackupObjectAead.decrypt(
                                BACKUP_ID, "objects/DB-IDENTITY", KEY, encrypted.encodedEnvelope()))
                .isEqualTo(plaintext);

        BackupManifestObject manifestObject =
                new BackupManifestObject(
                        "objects/DB-IDENTITY",
                        BackupObjectKind.DATABASE_IDENTITY,
                        encrypted.encodedEnvelope().length,
                        sha256(encrypted.encodedEnvelope()),
                        encrypted.encryptionMetadataReference(),
                        1);
        assertThat(manifestObject.encryptionMetadataReference())
                .isEqualTo(encrypted.encryptionMetadataReference());
    }

    @Test
    void decryptRejectsWrongBackupIdOrObjectPath() {
        EncryptedBackupObject encrypted =
                BackupObjectAead.encrypt(
                        BACKUP_ID,
                        "objects/CURRENT",
                        KEY,
                        7,
                        "current".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(
                        () ->
                                BackupObjectAead.decrypt(
                                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                        "objects/CURRENT",
                                        KEY,
                                        encrypted.encodedEnvelope()))
                .isInstanceOf(AetherDecryptionException.class);
        assertThatThrownBy(
                        () ->
                                BackupObjectAead.decrypt(
                                        BACKUP_ID,
                                        "objects/OTHER",
                                        KEY,
                                        encrypted.encodedEnvelope()))
                .isInstanceOf(AetherDecryptionException.class);
    }

    @Test
    void corruptionMutatorDrivesEncryptedBackupObjectFailures() {
        EncryptedBackupObject encrypted =
                BackupObjectAead.encrypt(
                        BACKUP_ID,
                        "objects/CURRENT",
                        KEY,
                        7,
                        "current".getBytes(StandardCharsets.UTF_8));

        byte[] badMagic =
                CorruptionMutator.apply(encrypted.encodedEnvelope(), CorruptionPlan.flipBit(0, 0));
        assertThatThrownBy(() -> BackupObjectAead.decrypt(BACKUP_ID, "objects/CURRENT", KEY, badMagic))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] truncated =
                CorruptionMutator.apply(
                        encrypted.encodedEnvelope(), CorruptionPlan.truncate(AeadEnvelope.NONCE_BYTES));
        assertThatThrownBy(() -> BackupObjectAead.decrypt(BACKUP_ID, "objects/CURRENT", KEY, truncated))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeObjectPath() {
        assertThatThrownBy(
                        () ->
                                BackupObjectAead.encrypt(
                                        BACKUP_ID,
                                        "../CURRENT",
                                        KEY,
                                        7,
                                        new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    private static byte[] sha256(byte[] contents) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(contents);
    }
}
