package io.aetherdb.io;

/** Durable object classes referenced by a portable backup manifest. */
public enum BackupObjectKind {
    DATABASE_IDENTITY(1),
    FORMAT_OPTIONS(2),
    CHECKPOINT_METADATA(3),
    CURRENT(4),
    MANIFEST(5),
    SSTABLE(6),
    WAL(7),
    SECURITY_METADATA(8),
    RAFT_METADATA(9),
    BACKUP_REPORT(10);

    private final int code;

    BackupObjectKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    static BackupObjectKind fromCode(int code) {
        for (BackupObjectKind kind : values()) if (kind.code == code) return kind;
        throw new IllegalArgumentException("unknown backup object kind");
    }
}
