package io.aetherdb.api.typed;

/**
 * Converts logical collection keys to and from their stable binary representation.
 *
 * @param <K> logical key type
 */
public interface KeyCodec<K> {
    /**
     * Returns the codec identity.
     *
     * @return the stable identifier for this codec family
     */
    String codecId();

    /**
     * Returns the encoding version.
     *
     * @return the positive version emitted by this codec
     */
    int encodingVersion();

    /**
     * Returns the key-size bound.
     *
     * @return the largest encoded key size, in bytes
     */
    int maximumEncodedSize();

    /**
     * Returns the codec fingerprint.
     *
     * @return a defensive copy of the 32-byte fingerprint
     */
    byte[] fingerprint();

    /**
     * Encodes a logical key.
     *
     * @param value non-null key to encode
     * @return encoded key bytes
     */
    byte[] encode(K value);

    /**
     * Decodes a key produced by this codec version.
     *
     * @param encoded encoded key bytes
     * @return decoded logical key
     */
    K decode(byte[] encoded);
}
