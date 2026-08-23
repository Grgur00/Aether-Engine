package io.aetherdb.crypto;

/** Raised when authenticated decryption fails. */
public final class AetherDecryptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AetherDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
