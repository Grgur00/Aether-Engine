package io.aetherdb.format.catalog;

/** Checksum or authentication policy for one format. */
public enum ChecksumPolicy {
    NONE,
    MASKED_CRC32C,
    AES_GCM_TAG,
    SHA256_MANIFEST,
    FORMAT_SPECIFIC
}
