package io.aetherdb.api.typed;

import java.util.UUID;

/** Encodes versioned collection values while preserving their durable schema identity.
 * @param <V> logical value type */
public interface ValueCodec<V> {
    /** Returns the schema identity.
     * @return immutable UUID identifying the value schema */
    UUID schemaId();

    /** Returns the current schema version.
     * @return version emitted by {@link #encode(Object)} */
    int currentSchemaVersion();

    /**
     * Computes an upper bound for one encoded value.
     *
     * @param value value that may be encoded
     * @return maximum encoded size, in bytes
     */
    int maximumEncodedSize(V value);

    /** Returns the schema fingerprint.
     * @return defensive copy of the 32-byte fingerprint */
    byte[] fingerprint();

    /**
     * Encodes a value using {@link #currentSchemaVersion()}.
     *
     * @param value non-null value to encode
     * @return encoded payload bytes
     */
    byte[] encode(V value);

    /**
     * Decodes a payload written with the supplied schema version.
     *
     * @param schemaVersion version recorded with the payload
     * @param encoded encoded payload bytes
     * @return decoded value
     */
    V decode(int schemaVersion, byte[] encoded);
}
