package io.aetherdb.codec.generated;

/** Chapter 23B payload-format-v1 wire type identifiers. */
public final class WireType {
    /** Canonical boolean payload. */
    public static final int BOOL = 1;

    /** Zigzag-encoded signed integer payload. */
    public static final int SIGNED_VARINT = 2;

    /** Canonical unsigned integer payload. */
    public static final int UNSIGNED_VARINT = 3;

    /** Little-endian fixed-width 32-bit payload. */
    public static final int FIXED32 = 4;

    /** Little-endian fixed-width 64-bit payload. */
    public static final int FIXED64 = 5;

    /** Bounded uninterpreted byte payload. */
    public static final int BYTES = 6;

    /** Strict UTF-8 string payload. */
    public static final int STRING_UTF8 = 7;

    /** Big-endian 128-bit UUID payload. */
    public static final int UUID128 = 8;

    /** Canonical arbitrary-precision numeric payload. */
    public static final int DECIMAL = 9;

    /** Canonical temporal payload. */
    public static final int TEMPORAL = 10;

    /** Exact signed eight-bit integer payload. */
    public static final int SIGNED_BYTE = 11;

    /** Bounded deterministic optional/list/set/map payload. */
    public static final int CONTAINER = 12;

    /** Stable positive numeric enum value. */
    public static final int ENUM = 13;

    /** Nested schema identity, version, and payload. */
    public static final int NESTED = 14;

    private WireType() {}
}
