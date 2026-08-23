package io.aetherdb.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM helper for Aether file/block/group encryption. */
public final class AetherAead {
    public static final int KEY_BYTES = 32;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AetherAead() {}

    public static AeadEnvelope encrypt(byte[] key, long keyEpoch, byte[] plaintext, byte[] aad) {
        validateKey(key);
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[AeadEnvelope.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return encryptWithNonceForTest(key, keyEpoch, nonce, plaintext, aad);
    }

    static AeadEnvelope encryptWithNonceForTest(
            byte[] key, long keyEpoch, byte[] nonce, byte[] plaintext, byte[] aad) {
        validateKey(key);
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(plaintext, "plaintext");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return new AeadEnvelope(AeadEnvelope.VERSION, keyEpoch, nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("AES-GCM encryption failed", failure);
        }
    }

    public static byte[] decrypt(byte[] key, AeadEnvelope envelope, byte[] aad) {
        validateKey(key);
        Objects.requireNonNull(envelope, "envelope");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, envelope.nonce()));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(envelope.ciphertext());
        } catch (GeneralSecurityException failure) {
            throw new AetherDecryptionException("AES-GCM authentication failed", failure);
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES)
            throw new IllegalArgumentException("Aether AES-GCM keys must be 32 bytes");
    }
}
