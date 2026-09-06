package io.aetherdb.training.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable SHA-256 identity of a deterministic training transformation. */
public final class TransformationFingerprint {
    public static final int BYTES = 32;
    private final byte[] digest;

    private TransformationFingerprint(byte[] digest) { this.digest = digest; }

    public static TransformationFingerprint ofCanonicalDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new TransformationFingerprint(sha256(descriptor.getBytes(StandardCharsets.UTF_8)));
    }

    public static TransformationFingerprint of(Map<String, ?> descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        TreeMap<String, String> sorted = new TreeMap<>();
        descriptor.forEach((key, value) -> {
            if (key == null || value == null) throw new IllegalArgumentException("descriptor contains null");
            sorted.put(key, String.valueOf(value));
        });
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((key, value) -> canonical.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value).append(';'));
        return ofCanonicalDescriptor(canonical.toString());
    }

    public byte[] bytes() { return digest.clone(); }

    public static TransformationFingerprint fromBytes(byte[] digest) {
        Objects.requireNonNull(digest, "digest");
        if (digest.length != BYTES) throw new IllegalArgumentException("fingerprint must be 32 bytes");
        return new TransformationFingerprint(digest.clone());
    }

    @Override public boolean equals(Object other) {
        return other instanceof TransformationFingerprint fingerprint
                && Arrays.equals(digest, fingerprint.digest);
    }

    @Override public int hashCode() { return Arrays.hashCode(digest); }

    @Override public String toString() {
        StringBuilder result = new StringBuilder(BYTES * 2);
        for (byte value : digest) result.append(String.format("%02x", value));
        return result.toString();
    }

    static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    static byte[] keyBytes(String namespace, String sampleId, TransformationFingerprint transform) {
        byte[] namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] sampleBytes = sampleId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(4 + namespaceBytes.length + 4 + sampleBytes.length + BYTES);
        input.putInt(namespaceBytes.length).put(namespaceBytes).putInt(sampleBytes.length).put(sampleBytes).put(transform.digest);
        return sha256(input.array());
    }
}
