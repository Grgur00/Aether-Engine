package io.aetherdb.crypto;

/** Wrapped data-encryption or master key metadata. */
public record WrappedKey(String wrappingKeyId, long keyEpoch, AeadEnvelope envelope) {
    public WrappedKey {
        if (wrappingKeyId == null || wrappingKeyId.isBlank())
            throw new IllegalArgumentException("blank wrapping key id");
        if (keyEpoch <= 0) throw new IllegalArgumentException("key epoch must be positive");
        if (envelope == null) throw new IllegalArgumentException("envelope is null");
    }
}
