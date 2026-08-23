package io.aetherdb.format.catalog;

import java.util.Objects;

/** Durable catalog entry for one concrete Aether format version. */
public record FormatDescriptor(
        String id,
        FormatKind kind,
        String magic,
        int version,
        int headerBytes,
        ByteOrderPolicy byteOrder,
        ChecksumPolicy checksum,
        CompatibilityPolicy compatibility,
        String ownerModule) {
    public FormatDescriptor {
        if (id == null || !id.matches("aether\\.[a-z0-9_.-]+\\.v[0-9]+"))
            throw new IllegalArgumentException("invalid format id");
        Objects.requireNonNull(kind, "kind");
        if (magic == null || magic.isBlank()) throw new IllegalArgumentException("blank magic");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (headerBytes < 0) throw new IllegalArgumentException("negative header size");
        Objects.requireNonNull(byteOrder, "byteOrder");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(compatibility, "compatibility");
        if (ownerModule == null || ownerModule.isBlank())
            throw new IllegalArgumentException("blank owner module");
    }
}
