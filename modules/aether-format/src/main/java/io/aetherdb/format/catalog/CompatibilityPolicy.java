package io.aetherdb.format.catalog;

/** Reader/writer compatibility policy for a durable format version. */
public enum CompatibilityPolicy {
    EXACT_VERSION_ONLY,
    BACKWARD_COMPATIBLE_READER,
    LENGTH_DELIMITED_OPTIONAL_FIELDS,
    TEXT_SCHEMA_COMPATIBLE
}
