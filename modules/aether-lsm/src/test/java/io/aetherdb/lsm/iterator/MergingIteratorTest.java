package io.aetherdb.lsm.iterator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MergingIteratorTest {
    @Test void mergeAndSnapshotCollapseChooseVisibleVersionAndMaskTombstone() {
        InternalIterator first = new ListInternalIterator(List.of(
                value("a", 7, "new"), value("a", 3, "old"), tombstone("b", 4)));
        InternalIterator second = new ListInternalIterator(List.of(
                value("a", 5, "middle"), value("b", 2, "present"), value("c", 1, "last")));
        List<String> rows = new ArrayList<>();
        try (var collapsed = new SnapshotCollapsingIterator(
                new MergingInternalIterator(List.of(first, second)), 5, bytes("a"), bytes("d"))) {
            while (collapsed.next()) rows.add(text(collapsed.key()) + "=" + text(collapsed.value()));
        }
        assertEquals(List.of("a=middle", "c=last"), rows);
    }

    @Test void exactDuplicateAcrossSourcesIsCorruption() {
        InternalEntry duplicate = value("a", 1, "x");
        try (var merged = new MergingInternalIterator(List.of(
                new ListInternalIterator(List.of(duplicate)), new ListInternalIterator(List.of(duplicate))))) {
            assertThrows(IllegalStateException.class, merged::next);
        }
    }

    @Test void halfOpenBoundsAreEnforced() {
        try (var collapsed = new SnapshotCollapsingIterator(
                new ListInternalIterator(List.of(value("a", 1, "1"), value("b", 2, "2"), value("c", 3, "3"))),
                3, bytes("b"), bytes("c"))) {
            assertTrue(collapsed.next()); assertEquals("b", text(collapsed.key())); assertFalse(collapsed.next());
        }
    }

    private static InternalEntry value(String key, long sequence, String value) { return InternalEntry.value(bytes(key), sequence, bytes(value)); }
    private static InternalEntry tombstone(String key, long sequence) { return InternalEntry.tombstone(bytes(key), sequence); }
    private static byte[] bytes(String value) { return value.getBytes(UTF_8); }
    private static String text(byte[] value) { return new String(value, UTF_8); }
}
