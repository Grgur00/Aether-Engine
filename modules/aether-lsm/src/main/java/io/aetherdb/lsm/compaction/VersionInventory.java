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

    public VersionInventory(List<? extends List<CompactionFile>> levels) { this(levels, emptyPointers()); }
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
    public List<CompactionFile> level(int level) { return levels.get(level); }
    public byte[] pointer(int level) { byte[] pointer = pointers.get(level); return pointer == null ? null : pointer.clone(); }
    public long levelBytes(int level) {
        long total = 0;
        for (CompactionFile file : levels.get(level)) total = Math.addExact(total, file.fileSize());
        return total;
    }
    public List<CompactionFile> overlaps(int level, byte[] smallest, byte[] largest) {
        return levels.get(level).stream().filter(file -> file.overlaps(smallest, largest)).toList();
    }
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
