package io.aetherdb.lsm.compaction;

import static org.junit.jupiter.api.Assertions.*;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.lsm.iterator.InternalEntry;
import io.aetherdb.lsm.iterator.ListInternalIterator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

final class CompactionPolicyTest {
    @Test
    void defaultTargetsAndScoresMatchSpecification() {
        LevelCompactionConfig config = LevelCompactionConfig.defaults();
        assertEquals(512 * LevelCompactionConfig.MIB, config.targetBytes(1));
        assertEquals(5 * LevelCompactionConfig.GIB, config.targetBytes(2));
        VersionInventory version =
                version(
                        List.of(
                                file(1, 0, "a", "b", 10), file(2, 0, "c", "d", 10),
                                file(3, 0, "e", "f", 10), file(4, 0, "g", "h", 10)),
                        List.of());
        assertEquals(1.0, new CompactionScoreCalculator(config).calculate(version).score(0));
    }

    @Test
    void l0PickerComputesStableClosureThroughL1() {
        List<CompactionFile> l0 =
                List.of(
                        file(10, 0, "a", "c", 10),
                        file(11, 0, "f", "h", 10),
                        file(12, 0, "e", "f", 10));
        List<CompactionFile> l1 = List.of(file(20, 1, "c", "e", 20));
        VersionInventory version = version(l0, l1);
        CompactionScores scores = new CompactionScores(new double[] {1, 0, 0, 0, 0, 0});
        CompactionPlan plan =
                new CompactionPickerV1(LevelCompactionConfig.defaults())
                        .pick(version, scores, 7)
                        .orElseThrow();
        assertEquals(
                List.of(10L, 11L, 12L),
                plan.primaryInputs().stream().map(CompactionFile::fileNumber).sorted().toList());
        assertEquals(
                List.of(20L),
                plan.outputLevelInputs().stream().map(CompactionFile::fileNumber).toList());
        assertArrayEquals(bytes("a"), plan.smallestUserKey());
        assertArrayEquals(bytes("h"), plan.largestUserKey());
    }

    @Test
    void urgentL0OverridesHigherLevelScore() {
        List<CompactionFile> l0 = new ArrayList<>();
        for (int i = 0; i < 12; i++) l0.add(file(i + 1, 0, "a" + i, "a" + i, 1));
        VersionInventory version = version(l0, List.of());
        CompactionPlan plan =
                new CompactionPickerV1(LevelCompactionConfig.defaults())
                        .pick(version, new CompactionScores(new double[] {3, 9, 0, 0, 0, 0}), 0)
                        .orElseThrow();
        assertEquals(0, plan.inputLevel());
        assertEquals(CompactionReason.URGENT_L0, plan.reason());
    }

    @Test
    void reclamationKeepsNewerVersionsAndOneBoundaryVersion() {
        var source =
                new ListInternalIterator(
                        List.of(
                                value("a", 9),
                                value("a", 7),
                                value("a", 5),
                                value("a", 2),
                                InternalEntry.tombstone(bytes("b"), 4),
                                value("b", 1)));
        List<Long> retained = new ArrayList<>();
        try (var dropping =
                new CompactionDroppingIterator(
                        source, 5, key -> new String(key, UTF_8).equals("b"))) {
            while (dropping.next()) retained.add(dropping.current().sequence());
            assertEquals(2, dropping.droppedVersions());
            assertEquals(1, dropping.droppedTombstones());
        }
        assertEquals(List.of(9L, 7L, 5L), retained);
    }

    @Test
    void baseLevelCheckSearchesOnlyLevelsBelowOutput() {
        List<List<CompactionFile>> levels = emptyLevels();
        levels.set(4, List.of(file(40, 4, "m", "z", 1)));
        VersionInventory version = new VersionInventory(levels);
        BaseLevelKeyChecker checker = new BaseLevelKeyChecker();
        assertFalse(checker.isBaseLevelForKey(bytes("n"), 2, version));
        assertTrue(checker.isBaseLevelForKey(bytes("a"), 2, version));
        assertTrue(checker.isBaseLevelForKey(bytes("n"), 4, version));
    }

    @Test
    void rangeRegistrationRejectsConflictsAndClosesIdempotently() {
        CompactionRangeRegistry registry = new CompactionRangeRegistry();
        var registration = registry.register(1, 2, bytes("a"), bytes("m"));
        assertThrows(
                IllegalStateException.class, () -> registry.register(2, 3, bytes("m"), bytes("z")));
        registration.close();
        registration.close();
        assertEquals(0, registry.activeCount());
    }

    private static VersionInventory version(List<CompactionFile> l0, List<CompactionFile> l1) {
        List<List<CompactionFile>> levels = emptyLevels();
        levels.set(0, l0);
        levels.set(1, l1);
        return new VersionInventory(levels);
    }

    private static List<List<CompactionFile>> emptyLevels() {
        List<List<CompactionFile>> result = new ArrayList<>();
        for (int i = 0; i < 7; i++) result.add(List.of());
        return result;
    }

    private static CompactionFile file(
            long number, int level, String smallest, String largest, long size) {
        return new CompactionFile(number, level, bytes(smallest), bytes(largest), size);
    }

    private static InternalEntry value(String key, long sequence) {
        return InternalEntry.value(bytes(key), sequence, bytes("v"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }
}
