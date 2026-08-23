package io.aetherdb.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Local AES-GCM key wrapper for development and file-backed keystore providers. */
public final class LocalKeyWrapper {
    private final String wrappingKeyId;
    private final byte[] wrappingKey;

    public LocalKeyWrapper(String wrappingKeyId, byte[] wrappingKey) {
        if (wrappingKeyId == null || wrappingKeyId.isBlank())
            throw new IllegalArgumentException("blank wrapping key id");
        if (wrappingKey == null || wrappingKey.length != AetherAead.KEY_BYTES)
            throw new IllegalArgumentException("wrapping key must be 32 bytes");
        this.wrappingKeyId = wrappingKeyId;
        this.wrappingKey = wrappingKey.clone();
    }

    public WrappedKey wrap(long keyEpoch, byte[] plaintextKey, String purpose) {
        Objects.requireNonNull(plaintextKey, "plaintextKey");
        byte[] aad = aad(keyEpoch, purpose);
        return new WrappedKey(
                wrappingKeyId, keyEpoch, AetherAead.encrypt(wrappingKey, keyEpoch, plaintextKey, aad));
    }

    public byte[] unwrap(WrappedKey wrapped, String purpose) {
        Objects.requireNonNull(wrapped, "wrapped");
        if (!wrappingKeyId.equals(wrapped.wrappingKeyId()))
            throw new AetherDecryptionException("wrapping key id mismatch", null);
        return AetherAead.decrypt(wrappingKey, wrapped.envelope(), aad(wrapped.keyEpoch(), purpose));
    }

    public void destroy() {
        Arrays.fill(wrappingKey, (byte) 0);
    }

    private static byte[] aad(long keyEpoch, String purpose) {
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("blank key purpose");
        return ("aether-key-wrap:v1:" + keyEpoch + ':' + purpose).getBytes(StandardCharsets.UTF_8);
    }
}
