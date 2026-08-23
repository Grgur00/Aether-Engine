package io.aetherdb.crypto;

import java.util.Objects;

/**
 * Encrypted portable backup object bytes plus the manifest metadata reference required to decrypt
 * them.
 *
 * @param encryptionMetadataReference stable manifest reference
 * @param encodedEnvelope encoded AEAD envelope stored as the archive object payload
 */
public record EncryptedBackupObject(String encryptionMetadataReference, byte[] encodedEnvelope) {
    public EncryptedBackupObject {
        Objects.requireNonNull(encryptionMetadataReference, "encryptionMetadataReference");
        if (encryptionMetadataReference.isBlank()
                || encryptionMetadataReference.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid encryption metadata reference");
        Objects.requireNonNull(encodedEnvelope, "encodedEnvelope");
        encodedEnvelope = encodedEnvelope.clone();
    }

    @Override
    public byte[] encodedEnvelope() {
        return encodedEnvelope.clone();
    }
}
