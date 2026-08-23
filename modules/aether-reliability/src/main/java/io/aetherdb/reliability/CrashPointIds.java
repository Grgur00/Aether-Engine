package io.aetherdb.reliability;

import java.util.Set;

/** Standard Chapter 29 deterministic crash-point IDs. */
public final class CrashPointIds {
    public static final String WRITE_BEFORE_SEAL = "write.before_seal";
    public static final String WRITE_AFTER_SEQUENCE_ALLOCATED = "write.after_sequence_allocated";
    public static final String WAL_BEFORE_FRAGMENT_WRITE = "wal.before_fragment_write";
    public static final String WAL_AFTER_FRAGMENT_WRITE_BEFORE_FORCE =
            "wal.after_fragment_write_before_force";
    public static final String WAL_AFTER_FORCE_BEFORE_VISIBILITY =
            "wal.after_force_before_visibility";
    public static final String MEMTABLE_BEFORE_APPLY = "memtable.before_apply";
    public static final String MEMTABLE_AFTER_APPLY_BEFORE_ACK = "memtable.after_apply_before_ack";
    public static final String FLUSH_AFTER_SSTABLE_FORCE_BEFORE_MANIFEST =
            "flush.after_sstable_force_before_manifest";
    public static final String MANIFEST_AFTER_APPEND_BEFORE_CURRENT =
            "manifest.after_append_before_current";
    public static final String MANIFEST_AFTER_CURRENT_BEFORE_DIR_SYNC =
            "manifest.after_current_before_dir_sync";
    public static final String COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST =
            "compaction.after_output_force_before_manifest";
    public static final String COMPACTION_AFTER_MANIFEST_BEFORE_DELETE =
            "compaction.after_manifest_before_delete";
    public static final String CHECKPOINT_AFTER_COPY_BEFORE_METADATA =
            "checkpoint.after_copy_before_metadata";
    public static final String REPAIR_AFTER_BACKUP_BEFORE_TRUNCATE =
            "repair.after_backup_before_truncate";
    public static final String RAFT_VOTE_AFTER_PERSIST_BEFORE_REPLY =
            "raft.vote.after_persist_before_reply";
    public static final String RAFT_APPEND_AFTER_LOG_PERSIST_BEFORE_REPLY =
            "raft.append.after_log_persist_before_reply";
    public static final String RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY =
            "raft.commit.after_majority_before_apply";
    public static final String SNAPSHOT_AFTER_FILE_WRITE_BEFORE_PUBLISH =
            "snapshot.after_file_write_before_publish";
    public static final String SECURITY_KEY_AFTER_PREPARE_BEFORE_ACTIVATE =
            "security.key.after_prepare_before_activate";

    private static final Set<String> ALL =
            Set.of(
                    WRITE_BEFORE_SEAL,
                    WRITE_AFTER_SEQUENCE_ALLOCATED,
                    WAL_BEFORE_FRAGMENT_WRITE,
                    WAL_AFTER_FRAGMENT_WRITE_BEFORE_FORCE,
                    WAL_AFTER_FORCE_BEFORE_VISIBILITY,
                    MEMTABLE_BEFORE_APPLY,
                    MEMTABLE_AFTER_APPLY_BEFORE_ACK,
                    FLUSH_AFTER_SSTABLE_FORCE_BEFORE_MANIFEST,
                    MANIFEST_AFTER_APPEND_BEFORE_CURRENT,
                    MANIFEST_AFTER_CURRENT_BEFORE_DIR_SYNC,
                    COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST,
                    COMPACTION_AFTER_MANIFEST_BEFORE_DELETE,
                    CHECKPOINT_AFTER_COPY_BEFORE_METADATA,
                    REPAIR_AFTER_BACKUP_BEFORE_TRUNCATE,
                    RAFT_VOTE_AFTER_PERSIST_BEFORE_REPLY,
                    RAFT_APPEND_AFTER_LOG_PERSIST_BEFORE_REPLY,
                    RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY,
                    SNAPSHOT_AFTER_FILE_WRITE_BEFORE_PUBLISH,
                    SECURITY_KEY_AFTER_PREPARE_BEFORE_ACTIVATE);

    private CrashPointIds() {}

    public static Set<String> all() {
        return ALL;
    }
}
