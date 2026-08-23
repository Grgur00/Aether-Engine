package io.aetherdb.io;

/** Restore identity policy selected by an operator before restore writes begin. */
public enum BackupRestoreMode {
    SINGLE_NODE_PRESERVE_DATABASE_ID,
    SINGLE_NODE_NEW_DATABASE_ID,
    CLUSTER_DISASTER_NEW_CLUSTER,
    CLUSTER_MEMBER_REPLACEMENT
}
