package io.aetherdb.codec.generated;

/** Chapter 23B payload-format-v1 wire type identifiers. */
public final class WireType {
    /** Canonical boolean payload. */
    public static final int BOOL = 1;
    /** Zigzag-encoded signed integer payload. */
    public static final int SIGNED_VARINT = 2;
    /** Little-endian fixed-width 64-bit payload. */
    public static final int FIXED64 = 5;
    /** Strict UTF-8 string payload. */
    public static final int STRING_UTF8 = 7;
    /** Big-endian 128-bit UUID payload. */
    public static final int UUID128 = 8;
    /** Canonical temporal payload. */
    public static final int TEMPORAL = 10;

    private WireType() {}
}
