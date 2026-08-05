package io.aetherdb.lsm.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable seven-level metadata snapshot with validated nonzero-level invariants. */
public final class VersionInventory {
    private final List<List<CompactionFile>> levels;
    private final List<byte[]> pointers;

    /** Creates an inventory with no compaction pointers.
     * @param levels exactly seven level file lists */
    public VersionInventory(List<? extends List<CompactionFile>> levels) { this(levels, emptyPointers()); }
    /** Creates and validates an inventory and its six non-final-level pointers.
     * @param levels exactly seven level file lists
     * @param pointers six nullable picker pointers */
    public VersionInventory(List<? extends List<CompactionFile>> levels, List<byte[]> pointers) {
        if (levels.size() != LevelCompactionConfig.LEVEL_COUNT || pointers.size() != 6)
            throw new IllegalArgumentException("exactly seven levels and six pointers are required");
        List<List<CompactionFile>> copied = new ArrayList<>(levels.size());
        Set<Long> fileNumbers = new HashSet<>();
        for (int level = 0; level < levels.size(); level++) {
            List<CompactionFile> files = List.copyOf(levels.get(level));
            for (CompactionFile file : files) {
                if (file.level() != level || !fileNumbers.add(file.fileNumber()))
                    throw new IllegalArgumentException("invalid or duplicate file metadata");
            }
            if (level > 0) validateNonOverlapping(files);
            copied.add(files);
        }
        this.levels = List.copyOf(copied);
        List<byte[]> pointerCopies = new ArrayList<>(6);
        for (byte[] pointer : pointers) pointerCopies.add(pointer == null ? null : pointer.clone());
        this.pointers = Collections.unmodifiableList(pointerCopies);
    }
    /** Returns files in one level.
     * @param level level number
     * @return immutable file list */
    public List<CompactionFile> level(int level) { return levels.get(level); }
    /** Returns a level's picker pointer.
     * @param level level zero through five
     * @return defensive key copy or null */
    public byte[] pointer(int level) { byte[] pointer = pointers.get(level); return pointer == null ? null : pointer.clone(); }
    /** Sums encoded bytes in one level.
     * @param level level number
     * @return total file bytes */
    public long levelBytes(int level) {
        long total = 0;
        for (CompactionFile file : levels.get(level)) total = Math.addExact(total, file.fileSize());
        return total;
    }
    /** Finds files overlapping an inclusive key range.
     * @param level level number
     * @param smallest inclusive lower key
     * @param largest inclusive upper key
     * @return immutable overlapping file list */
    public List<CompactionFile> overlaps(int level, byte[] smallest, byte[] largest) {
        return levels.get(level).stream().filter(file -> file.overlaps(smallest, largest)).toList();
    }
    /** Tests file-number membership.
     * @param number file number
     * @return whether present in any level */
    public boolean containsFile(long number) { return levels.stream().flatMap(List::stream).anyMatch(file -> file.fileNumber() == number); }
    private static void validateNonOverlapping(List<CompactionFile> files) {
        for (int i = 1; i < files.size(); i++)
            if (Arrays.compareUnsigned(files.get(i - 1).largestUserKey(), files.get(i).smallestUserKey()) >= 0)
                throw new IllegalArgumentException("nonzero level files must be sorted and non-overlapping");
    }
    private static List<byte[]> emptyPointers() {
        List<byte[]> result = new ArrayList<>(6); for (int i = 0; i < 6; i++) result.add(null); return result;
    }
}
