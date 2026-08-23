package io.aetherdb.format.catalog;

/** Broad class of Aether persisted or wire format. */
public enum FormatKind {
    STORAGE_FILE,
    STORAGE_RECORD,
    METADATA_FILE,
    WIRE_FRAME,
    SCHEMA_LOCK,
    SECURITY_METADATA,
    BACKUP_OBJECT
}
