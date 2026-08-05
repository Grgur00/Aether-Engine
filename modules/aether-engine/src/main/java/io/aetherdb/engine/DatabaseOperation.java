package io.aetherdb.engine;

/** Database operations measured by an instrumented Aether database. */
public enum DatabaseOperation {
    /** Single-key insertion or replacement. */
    PUT,
    /** Single-key deletion. */
    DELETE,
    /** Point lookup, including snapshot lookups. */
    GET,
    /** Cursor creation for range and full-database scans. */
    SCAN,
    /** Snapshot creation. */
    SNAPSHOT,
    /** Atomic batch submission. */
    WRITE
}
