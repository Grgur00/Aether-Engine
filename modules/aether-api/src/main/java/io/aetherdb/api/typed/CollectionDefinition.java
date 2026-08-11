package io.aetherdb.api.typed;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Immutable identity, codec, and capability contract for a typed collection.
 *
 * @param id durable collection identity
 * @param name human-readable UTF-8 name of at most 128 bytes
 * @param keyCodec codec governing stored keys
 * @param valueCodec codec governing stored values
 * @param capabilities supported operations
 * @param <K> logical key type
 * @param <V> logical value type
 */
public record CollectionDefinition<K, V>(
        CollectionId id,
        String name,
        KeyCodec<K> keyCodec,
        ValueCodec<V> valueCodec,
        Set<CollectionCapability> capabilities) {
    /** Validates codec bounds and normalizes the capability set. */
    public CollectionDefinition {
        if (id == null
                || name == null
                || name.isBlank()
                || name.getBytes(StandardCharsets.UTF_8).length > 128
                || keyCodec == null
                || valueCodec == null) {
            throw new IllegalArgumentException("invalid collection definition");
        }
        if (keyCodec.encodingVersion() < 1
                || keyCodec.encodingVersion() > 65535
                || keyCodec.maximumEncodedSize() < 0
                || keyCodec.maximumEncodedSize() > 65517) {
            throw new IllegalArgumentException("invalid key codec bounds");
        }
        if (keyCodec.fingerprint() == null
                || keyCodec.fingerprint().length != 32
                || valueCodec.fingerprint() == null
                || valueCodec.fingerprint().length != 32) {
            throw new IllegalArgumentException("codec fingerprints must be 32 bytes");
        }
        capabilities =
                capabilities == null
                        ? Set.of(CollectionCapability.POINT_READ, CollectionCapability.POINT_WRITE)
                        : Set.copyOf(capabilities);
        if (capabilities.contains(CollectionCapability.RANGE_SCAN)
                && !(keyCodec instanceof OrderedKeyCodec<?>)) {
            throw new IllegalArgumentException("range scans require an ordered key codec");
        }
    }

    /**
     * Creates a definition supporting point reads and writes.
     *
     * @param id durable collection identity
     * @param name human-readable collection name
     * @param keyCodec key codec
     * @param valueCodec value codec
     * @param <K> logical key type
     * @param <V> logical value type
     * @return validated collection definition
     */
    public static <K, V> CollectionDefinition<K, V> of(
            CollectionId id, String name, KeyCodec<K> keyCodec, ValueCodec<V> valueCodec) {
        return new CollectionDefinition<>(
                id,
                name,
                keyCodec,
                valueCodec,
                Set.of(CollectionCapability.POINT_READ, CollectionCapability.POINT_WRITE));
    }
}
