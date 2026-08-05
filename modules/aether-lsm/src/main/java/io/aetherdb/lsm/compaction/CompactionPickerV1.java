package io.aetherdb.lsm.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure leveled-compaction picker implementing stable L0 overlap closure. */
public final class CompactionPickerV1 {
    private final LevelCompactionConfig config;
    public CompactionPickerV1(LevelCompactionConfig config) { this.config = Objects.requireNonNull(config); }

    public Optional<CompactionPlan> pick(VersionInventory version, CompactionScores scores, long oldestSnapshotSequence) {
        int level = selectedLevel(version, scores);
        if (level < 0) return Optional.empty();
        CompactionReason reason = version.level(0).size() >= 20 && level == 0 ? CompactionReason.WRITE_STOP
                : version.level(0).size() >= 12 && level == 0 ? CompactionReason.URGENT_L0 : CompactionReason.SCORE;
        return Optional.of(level == 0 ? pickL0(version, scores.score(0), oldestSnapshotSequence, reason)
                : pickNonzero(version, level, scores.score(level), oldestSnapshotSequence, reason));
    }

    private int selectedLevel(VersionInventory version, CompactionScores scores) {
        if (version.level(0).size() >= 12) return 0;
        int selected = -1; double best = 1.0;
        for (int level = 0; level <= 5; level++) {
            double score = scores.score(level);
            if (score > best || score == best && selected > level) { best = score; selected = level; }
            else if (score == 1.0 && selected < 0) selected = level;
        }
        return selected;
    }

    private CompactionPlan pickL0(VersionInventory version, double score, long snapshot, CompactionReason reason) {
        List<CompactionFile> primary = new ArrayList<>();
        CompactionFile seed = version.level(0).stream().min(Comparator.comparingLong(CompactionFile::fileNumber)).orElseThrow();
        primary.add(seed);
        byte[] smallest = seed.smallestUserKey(), largest = seed.largestUserKey();
        List<CompactionFile> output = List.of();
        boolean changed;
        do {
            changed = false;
            for (CompactionFile file : version.level(0)) if (!primary.contains(file) && file.overlaps(smallest, largest)) {
                primary.add(file); smallest = minimum(smallest, file.smallestUserKey()); largest = maximum(largest, file.largestUserKey()); changed = true;
            }
            List<CompactionFile> nextOutput = version.overlaps(1, smallest, largest);
            for (CompactionFile file : nextOutput) {
                byte[] newSmall = minimum(smallest, file.smallestUserKey()), newLarge = maximum(largest, file.largestUserKey());
                if (!Arrays.equals(newSmall, smallest) || !Arrays.equals(newLarge, largest)) { smallest = newSmall; largest = newLarge; changed = true; }
            }
            output = nextOutput;
        } while (changed);
        return plan(version, 0, primary, output, smallest, largest, snapshot, reason, score);
    }

    private CompactionPlan pickNonzero(VersionInventory version, int level, double score, long snapshot, CompactionReason reason) {
        List<CompactionFile> files = version.level(level);
        byte[] pointer = version.pointer(level);
        CompactionFile seed = files.get(0);
        if (pointer != null) for (CompactionFile file : files) if (Arrays.compareUnsigned(file.largestUserKey(), pointer) > 0) { seed = file; break; }
        List<CompactionFile> primary = List.of(seed);
        byte[] smallest = seed.smallestUserKey(), largest = seed.largestUserKey();
        List<CompactionFile> output = version.overlaps(level + 1, smallest, largest);
        if (!output.isEmpty()) {
            byte[] combinedSmall = smallest, combinedLarge = largest;
            for (CompactionFile file : output) { combinedSmall = minimum(combinedSmall, file.smallestUserKey()); combinedLarge = maximum(combinedLarge, file.largestUserKey()); }
            List<CompactionFile> expanded = version.overlaps(level, combinedSmall, combinedLarge);
            List<CompactionFile> expandedOutput = version.overlaps(level + 1, combinedSmall, combinedLarge);
            long initialBytes = bytes(primary) + bytes(output), expandedBytes = bytes(expanded) + bytes(expandedOutput);
            if (expandedOutput.equals(output) && expandedBytes <= Math.max(512 * LevelCompactionConfig.MIB, 2 * initialBytes)) {
                primary = expanded; smallest = combinedSmall; largest = combinedLarge;
            }
        }
        return plan(version, level, primary, output, smallest, largest, snapshot, reason, score);
    }

    private CompactionPlan plan(VersionInventory version, int level, List<CompactionFile> primary, List<CompactionFile> output,
            byte[] smallest, byte[] largest, long snapshot, CompactionReason reason, double score) {
        int outputLevel = level + 1;
        List<CompactionFile> grandparents = outputLevel < 6 ? version.overlaps(outputLevel + 1, smallest, largest) : List.of();
        long target = config.targetOutputFileBytes(outputLevel);
        return new CompactionPlan(level, outputLevel, primary, output, grandparents, smallest, largest, snapshot,
                target, Math.multiplyExact(10, target), bytes(primary) + bytes(output), reason, score,
                primary.get(primary.size() - 1).largestUserKey());
    }
    private static long bytes(List<CompactionFile> files) { long result = 0; for (CompactionFile file : files) result = Math.addExact(result, file.fileSize()); return result; }
    private static byte[] minimum(byte[] a, byte[] b) { return Arrays.compareUnsigned(a, b) <= 0 ? a : b; }
    private static byte[] maximum(byte[] a, byte[] b) { return Arrays.compareUnsigned(a, b) >= 0 ? a : b; }
}
