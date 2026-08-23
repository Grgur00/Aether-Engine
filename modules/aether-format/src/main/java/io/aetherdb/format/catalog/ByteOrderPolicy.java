package io.aetherdb.format.catalog;

/** Byte order used by a persisted or wire format. */
public enum ByteOrderPolicy {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
    TEXT_JSON,
    MIXED
}
