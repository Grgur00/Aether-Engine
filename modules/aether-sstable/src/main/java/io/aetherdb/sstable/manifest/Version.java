package io.aetherdb.sstable.manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable set of live files and durable recovery watermarks. */
public final class Version {
    private static final Comparator<ManifestFileMetadata> L0_ORDER =
            Comparator.comparingLong(ManifestFileMetadata::fileNumber).reversed();
    private static final Comparator<ManifestFileMetadata> LEVEL_ORDER =
            (left, right) -> Arrays.compareUnsigned(left.smallestUserKey(), right.smallestUserKey());
    private final List<List<ManifestFileMetadata>> levels;
    private final long nextFileNumber;
    private final long lastAssignedSequence;
    private final long persistedSequenceWatermark;
    private final long minimumWalFileNumber;
    private final long manifestGeneration;
    private final long manifestEditNumber;

    private Version(List<List<ManifestFileMetadata>> levels, ManifestEdit edit, long generation) {
        this.levels = levels; this.nextFileNumber = edit.nextFileNumber();
        this.lastAssignedSequence = edit.lastAssignedSequence();
        this.persistedSequenceWatermark = edit.persistedSequenceWatermark();
        this.minimumWalFileNumber = edit.minimumWalFileNumber(); this.manifestGeneration = generation;
        this.manifestEditNumber = edit.editNumber();
    }

    /** Builds a version from a snapshot edit.
     * @param snapshot authoritative complete snapshot
     * @param manifestGeneration owning manifest generation
     * @return validated immutable version */
    public static Version fromSnapshot(ManifestEdit snapshot, long manifestGeneration) {
        if (snapshot == null || snapshot.kind() != ManifestEdit.Kind.SNAPSHOT || manifestGeneration <= 0) {
            throw new IllegalArgumentException("snapshot and generation are required");
        }
        return applyTo(null, snapshot, manifestGeneration);
    }

    /** Applies a contiguous delta and returns a new validated version.
     * @param delta next contiguous edit
     * @return new immutable version */
    public Version apply(ManifestEdit delta) {
        if (delta == null || delta.kind() != ManifestEdit.Kind.DELTA) throw new IllegalArgumentException("delta required");
        return applyTo(this, delta, manifestGeneration);
    }

    private static Version applyTo(Version base, ManifestEdit edit, long generation) {
        Map<Long, ManifestFileMetadata> files = new HashMap<>();
        if (base != null) for (List<ManifestFileMetadata> level : base.levels) for (ManifestFileMetadata file : level) files.put(file.fileNumber(), file);
        for (ManifestDeletion deletion : edit.deletions()) {
            ManifestFileMetadata removed = files.remove(deletion.fileNumber());
            if (removed == null || removed.level() != deletion.level()) throw new IllegalArgumentException("deletion does not name a live file");
        }
        for (ManifestFileMetadata addition : edit.additions()) {
            if (files.putIfAbsent(addition.fileNumber(), addition) != null) throw new IllegalArgumentException("addition reuses a live file number");
        }
        if (base != null && (edit.editNumber() != base.manifestEditNumber + 1 || edit.nextFileNumber() < base.nextFileNumber
                || edit.lastAssignedSequence() < base.lastAssignedSequence
                || edit.persistedSequenceWatermark() < base.persistedSequenceWatermark
                || edit.minimumWalFileNumber() < base.minimumWalFileNumber)) {
            throw new IllegalArgumentException("manifest counters moved backwards");
        }
        List<List<ManifestFileMetadata>> levels = new ArrayList<>(7);
        for (int number = 0; number < 7; number++) {
            int levelNumber = number;
            List<ManifestFileMetadata> level = files.values().stream().filter(file -> file.level() == levelNumber)
                    .sorted(number == 0 ? L0_ORDER : LEVEL_ORDER).toList();
            if (number > 0) validateNonOverlapping(level);
            levels.add(level);
        }
        return new Version(List.copyOf(levels), edit, generation);
    }

    private static void validateNonOverlapping(List<ManifestFileMetadata> level) {
        for (int index = 1; index < level.size(); index++) {
            if (Arrays.compareUnsigned(level.get(index - 1).largestUserKey(), level.get(index).smallestUserKey()) >= 0) {
                throw new IllegalArgumentException("level files overlap or touch out of order");
            }
        }
    }

    /** Returns immutable files for a level.
     * @param level level number from zero through six
     * @return files in canonical level order */
    public List<ManifestFileMetadata> files(int level) {
        if (level < 0 || level >= levels.size()) throw new IllegalArgumentException("invalid level");
        return levels.get(level);
    }
    /** Returns all live files in level order.
     * @return flattened immutable live-file list */
    public List<ManifestFileMetadata> allFiles() { return levels.stream().flatMap(List::stream).toList(); }
    /** Returns the first unallocated file number.
     * @return positive allocation counter */ public long nextFileNumber() { return nextFileNumber; }
    /** Returns the highest assigned sequence.
     * @return nonnegative sequence */ public long lastAssignedSequence() { return lastAssignedSequence; }
    /** Returns the highest sequence represented by tables.
     * @return nonnegative flush watermark */ public long persistedSequenceWatermark() { return persistedSequenceWatermark; }
    /** Returns the oldest WAL still needed for recovery.
     * @return positive WAL file number */ public long minimumWalFileNumber() { return minimumWalFileNumber; }
    /** Returns the manifest generation containing this version.
     * @return positive generation */ public long manifestGeneration() { return manifestGeneration; }
    /** Returns the last applied physical/edit record number.
     * @return positive contiguous edit number */ public long manifestEditNumber() { return manifestEditNumber; }
}
