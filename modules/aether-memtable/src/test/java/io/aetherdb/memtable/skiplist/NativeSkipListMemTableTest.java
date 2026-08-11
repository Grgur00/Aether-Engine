package io.aetherdb.memtable.skiplist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.memory.NativeMemoryBudget;
import io.aetherdb.memory.RegionConfig;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

class NativeSkipListMemTableTest {
    @Test
    void headLayoutHeightDistributionAndOrderingMatchSpecification() {
        SkipListHeightGenerator generator = new SkipListHeightGenerator(42);
        long promoted =
                IntStream.range(0, 100_000)
                        .map(ignored -> generator.nextHeight())
                        .filter(height -> height >= 2)
                        .count();
        assertThat(promoted).isBetween(23_000L, 27_000L);
        assertThat(NativeSkipListNodeFormat.prefixBytes(1)).isEqualTo(24);
        assertThat(NativeSkipListNodeFormat.prefixBytes(2)).isEqualTo(24);

        NativeMemoryBudget budget = new NativeMemoryBudget(RegionConfig.MIN_CAPACITY_BYTES);
        try (NativeSkipListMemTable table =
                new NativeSkipListMemTable(budget, RegionConfig.MIN_CAPACITY_BYTES, "layout", 42)) {
            assertThat(table.headOffset()).isEqualTo(64);
            assertThat(table.nativeUsedBytes()).isEqualTo(96);
        }
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void latestSnapshotsTombstonesAndScansWorkOffHeap() {
        NativeMemoryBudget budget = new NativeMemoryBudget(RegionConfig.MIN_CAPACITY_BYTES);
        try (NativeSkipListMemTable table =
                new NativeSkipListMemTable(
                        budget, RegionConfig.MIN_CAPACITY_BYTES, "semantic", 7)) {
            assertThat(table.put(bytes("b"), bytes("b1"), 1))
                    .isEqualTo(NativeSkipListMemTable.InsertResult.INSERTED);
            table.put(bytes("a"), bytes("old"), 2);
            table.delete(bytes("a"), 3);
            table.put(bytes("a"), bytes("new"), 4);
            assertThat(table.get(bytes("a"), 4).value()).isEqualTo(bytes("new"));
            assertThat(table.get(bytes("a"), 3).kind())
                    .isEqualTo(MemTableLookupResult.Kind.TOMBSTONE);
            assertThat(table.get(bytes("a"), 2).value()).isEqualTo(bytes("old"));
            assertThat(table.get(bytes("missing"), 4).kind())
                    .isEqualTo(MemTableLookupResult.Kind.NOT_FOUND);
            List<NativeSkipListMemTable.Entry> rows = table.scan(bytes("a"), bytes("c"), 2);
            assertThat(rows)
                    .extracting(entry -> new String(entry.key(), StandardCharsets.UTF_8))
                    .containsExactly("a", "b");
            assertThat(table.put(bytes("a"), bytes("duplicate"), 4))
                    .isEqualTo(NativeSkipListMemTable.InsertResult.DUPLICATE);
        }
    }

    @Test
    void concurrentWritersFreezeAndLeaseRetirementAreSafe() {
        long capacity = 4L * RegionConfig.MIN_CAPACITY_BYTES;
        NativeMemoryBudget budget = new NativeMemoryBudget(capacity);
        NativeSkipListMemTable table =
                new NativeSkipListMemTable(budget, capacity, "concurrent", 99);
        IntStream.range(1, 2_001)
                .parallel()
                .forEach(
                        sequence ->
                                assertThat(
                                                table.put(
                                                        new byte[] {
                                                            (byte) (sequence >>> 8), (byte) sequence
                                                        },
                                                        new byte[] {1},
                                                        sequence))
                                        .isEqualTo(NativeSkipListMemTable.InsertResult.INSERTED));
        assertThat(table.entryCount()).isEqualTo(2_000);
        NativeSkipListMemTable.Lease lease = table.retain();
        table.freeze();
        assertThat(table.put(bytes("x"), bytes("y"), 3_000))
                .isEqualTo(NativeSkipListMemTable.InsertResult.FROZEN);
        table.retire();
        assertThat(budget.reservedBytes()).isEqualTo(capacity);
        assertThat(lease.table().get(new byte[] {0, 1}, 2_000).kind())
                .isEqualTo(MemTableLookupResult.Kind.VALUE);
        lease.close();
        assertThat(table.state()).isEqualTo(NativeSkipListMemTable.State.CLOSED);
        assertThat(budget.reservedBytes()).isZero();
        assertThatThrownBy(table::retain).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void frozenInternalIterationPreservesVersionsAndTombstonesForFlush() {
        NativeMemoryBudget budget = new NativeMemoryBudget(RegionConfig.MIN_CAPACITY_BYTES);
        try (NativeSkipListMemTable table =
                new NativeSkipListMemTable(budget, RegionConfig.MIN_CAPACITY_BYTES, "flush", 11)) {
            table.put(bytes("a"), bytes("old"), 1);
            table.delete(bytes("a"), 2);
            table.put(bytes("b"), bytes("value"), 3);
            assertThatThrownBy(table::internalEntries).isInstanceOf(IllegalStateException.class);
            table.freeze();
            List<NativeSkipListMemTable.InternalEntry> entries = table.internalEntries();
            assertThat(entries).hasSize(3);
            assertThat(entries)
                    .extracting(NativeSkipListMemTable.InternalEntry::sequence)
                    .containsExactly(2L, 1L, 3L);
            assertThat(entries)
                    .extracting(NativeSkipListMemTable.InternalEntry::tombstone)
                    .containsExactly(true, false, false);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
