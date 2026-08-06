package io.aetherdb.sstable.manifest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One complete manifest snapshot or incremental version edit.
 * @param kind snapshot or delta kind
 * @param editNumber contiguous physical record number
 * @param nextFileNumber first unallocated file number
 * @param lastAssignedSequence highest assigned MVCC sequence
 * @param persistedSequenceWatermark highest sequence represented by tables
 * @param minimumWalFileNumber oldest WAL required for recovery
 * @param additions tables added by this edit
 * @param deletions tables removed by this edit */
public record ManifestEdit(
        Kind kind, long editNumber, long nextFileNumber, long lastAssignedSequence,
        long persistedSequenceWatermark, long minimumWalFileNumber,
        List<ManifestFileMetadata> additions, List<ManifestDeletion> deletions) {
    /** Durable edit kind. */
    public enum Kind {
        /** Complete live-file snapshot. */ SNAPSHOT,
        /** Incremental change from the preceding version. */ DELTA
    }

    /** Validates edit counters and rejects duplicate or contradictory operations. */
    public ManifestEdit {
        if (kind == null || editNumber <= 0 || nextFileNumber <= 0 || lastAssignedSequence < 0
                || persistedSequenceWatermark < 0 || persistedSequenceWatermark > lastAssignedSequence
                || minimumWalFileNumber < 0 || kind == Kind.DELTA && minimumWalFileNumber == 0
                || additions == null || deletions == null
                || kind == Kind.SNAPSHOT && !deletions.isEmpty()) {
            throw new IllegalArgumentException("invalid manifest edit");
        }
        additions = List.copyOf(additions); deletions = List.copyOf(deletions);
        Set<Long> added = new HashSet<>(), deleted = new HashSet<>();
        for (ManifestFileMetadata file : additions) {
            if (!added.add(file.fileNumber()) || file.fileNumber() >= nextFileNumber) {
                throw new IllegalArgumentException("duplicate or unallocated added file");
            }
        }
        for (ManifestDeletion deletion : deletions) {
            if (!deleted.add(deletion.fileNumber()) || added.contains(deletion.fileNumber())) {
                throw new IllegalArgumentException("duplicate or contradictory deleted file");
            }
        }
    }
}
