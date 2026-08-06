package io.aetherdb.sstable.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aetherdb.sstable.InternalKey;
import io.aetherdb.sstable.SSTableBuilder;
import io.aetherdb.sstable.TableFileMetadata;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestFormatV1Test {
    private static final UUID DATABASE_ID = UUID.fromString("d9ce3e42-1d8d-48b4-a55b-04c81dbfed9c");

    @Test void currentRoundTripsAndRejectsCorruption() {
        byte[] encoded = CurrentFileV1.encode(DATABASE_ID, 42);
        CurrentFileV1.Pointer pointer = CurrentFileV1.decode(encoded, DATABASE_ID);
        assertEquals(42, pointer.generation());
        assertEquals("MANIFEST-00000000000000000042.aeman", pointer.manifestName());
        encoded[28] ^= 1;
        assertThrows(ManifestCorruptionException.class, () -> CurrentFileV1.decode(encoded, DATABASE_ID));
    }

    @Test void headerRoundTripsAndRejectsReservedBytes() {
        ManifestHeaderV1 expected = new ManifestHeaderV1(DATABASE_ID, 7, 1234, 9, 12);
        assertEquals(expected, ManifestHeaderV1.decodeRegion(expected.encodeRegion()));
        byte[] invalid = expected.encodeRegion(); invalid[100] = 1;
        assertThrows(ManifestCorruptionException.class, () -> ManifestHeaderV1.decodeRegion(invalid));
    }

    @Test void recordsUseDeterministicBytesAndRoundTripEveryField() {
        ManifestEdit edit = new ManifestEdit(ManifestEdit.Kind.SNAPSHOT, 1, 10, 51, 50, 3,
                List.of(file(8, 0, "a", 51, "m", 50), file(9, 1, "n", 49, "z", 40)), List.of());
        byte[] first = ManifestCodecV1.encodeRecord(edit), second = ManifestCodecV1.encodeRecord(edit);
        assertArrayEquals(first, second);
        ManifestEdit decoded = ManifestCodecV1.decodeRecord(first);
        assertEquals(edit.kind(), decoded.kind()); assertEquals(edit.editNumber(), decoded.editNumber());
        assertEquals(edit.nextFileNumber(), decoded.nextFileNumber()); assertEquals(2, decoded.additions().size());
        for (int index = 0; index < edit.additions().size(); index++) {
            org.junit.jupiter.api.Assertions.assertTrue(edit.additions().get(index).contentEquals(decoded.additions().get(index)));
        }
        first[first.length - 1] ^= 1;
        assertThrows(ManifestCorruptionException.class, () -> ManifestCodecV1.decodeRecord(first));
    }

    @Test void versionAppliesContiguousDeltaAndEnforcesLevelInvariants() {
        ManifestEdit snapshot = new ManifestEdit(ManifestEdit.Kind.SNAPSHOT, 1, 4, 20, 10, 1,
                List.of(file(2, 1, "a", 10, "m", 8), file(3, 0, "x", 20, "z", 19)), List.of());
        Version initial = Version.fromSnapshot(snapshot, 1);
        ManifestEdit delta = new ManifestEdit(ManifestEdit.Kind.DELTA, 2, 6, 30, 25, 2,
                List.of(file(5, 1, "n", 25, "z", 21)), List.of(new ManifestDeletion(3, 0)));
        Version next = initial.apply(delta);
        assertEquals(List.of(2L, 5L), next.files(1).stream().map(ManifestFileMetadata::fileNumber).toList());
        assertEquals(2, next.manifestEditNumber()); assertEquals(25, next.persistedSequenceWatermark());

        ManifestEdit gap = new ManifestEdit(ManifestEdit.Kind.DELTA, 4, 7, 30, 25, 2, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> next.apply(gap));
        ManifestEdit overlap = new ManifestEdit(ManifestEdit.Kind.DELTA, 3, 7, 31, 26, 2,
                List.of(file(6, 1, "m", 26, "p", 22)), List.of());
        assertThrows(IllegalArgumentException.class, () -> next.apply(overlap));
    }

    @Test void versionSetPublishesRecoversAndRepairsOnlyAnIncompleteTail(@TempDir Path root) throws Exception {
        ManifestEdit snapshot = new ManifestEdit(ManifestEdit.Kind.SNAPSHOT, 1, 2, 0, 0, 1, List.of(), List.of());
        SSTableBuilder builder = new SSTableBuilder(root.resolve(VersionSet.sstableName(2)), 2, DATABASE_ID, 100);
        builder.add(new InternalKey("a".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, (byte) 1), new byte[] {9});
        TableFileMetadata built = builder.finish();
        ManifestFileMetadata table = new ManifestFileMetadata(2, 0, built.fileSize(), built.entryCount(),
                built.smallestSequence(), built.largestSequence(), built.smallestInternalKey(), built.largestInternalKey());
        Path manifest;
        try (VersionSet versions = VersionSet.create(root, DATABASE_ID, 1, snapshot, 100)) {
            ManifestEdit delta = new ManifestEdit(ManifestEdit.Kind.DELTA, 2, 3, 1, 1, 1, List.of(table), List.of());
            assertEquals(2, versions.logAndApply(delta).manifestEditNumber()); manifest = versions.manifestPath();
        }
        long completeSize = Files.size(manifest);
        Files.write(manifest, new byte[] {1, 2, 3, 4, 5}, java.nio.file.StandardOpenOption.APPEND);
        ManifestInspection inspection = VersionSet.inspect(root, DATABASE_ID);
        assertEquals(5, inspection.incompleteTailBytes()); assertEquals(completeSize + 5, Files.size(manifest));
        try (VersionSet recovered = VersionSet.recover(root, DATABASE_ID)) {
            assertEquals(2, recovered.current().manifestEditNumber());
            assertEquals(2, recovered.current().files(0).get(0).fileNumber());
        }
        assertEquals(completeSize, Files.size(manifest));
    }

    private static ManifestFileMetadata file(long number, int level, String smallestUser, long smallestSequence,
                                             String largestUser, long largestSequence) {
        byte[] smallest = new InternalKey(smallestUser.getBytes(java.nio.charset.StandardCharsets.UTF_8), smallestSequence, (byte) 1).encode();
        byte[] largest = new InternalKey(largestUser.getBytes(java.nio.charset.StandardCharsets.UTF_8), largestSequence, (byte) 1).encode();
        return new ManifestFileMetadata(number, level, 4096 + number, 2,
                Math.min(smallestSequence, largestSequence), Math.max(smallestSequence, largestSequence), smallest, largest);
    }
}
