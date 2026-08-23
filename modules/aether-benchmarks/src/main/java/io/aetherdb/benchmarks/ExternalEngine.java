package io.aetherdb.benchmarks;

/** External engines supported by Chapter 30 same-machine comparison manifests. */
public enum ExternalEngine {
    /** Aether baseline or candidate. */
    AETHER,
    /** RocksDB comparison target. */
    ROCKSDB,
    /** LevelDB comparison target. */
    LEVELDB,
    /** LMDB comparison target. */
    LMDB,
    /** SQLite comparison target. */
    SQLITE
}
