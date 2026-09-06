package io.aetherdb.training.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stable identity of one source sample and one transformation generation. */
public record CacheKey(String namespace, String sampleId, TransformationFingerprint transform) {
    public CacheKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(sampleId, "sampleId");
        Objects.requireNonNull(transform, "transform");
        if (namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        if (sampleId.isEmpty()) throw new IllegalArgumentException("sampleId must not be empty");
    }

    byte[] storageKey() {
        byte[] namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
        byte[] digest = TransformationFingerprint.keyBytes(namespace, sampleId, transform);
        return ByteBuffer.allocate(4 + namespaceBytes.length + digest.length)
                .putInt(namespaceBytes.length).put(namespaceBytes).put(digest).array();
    }
}
