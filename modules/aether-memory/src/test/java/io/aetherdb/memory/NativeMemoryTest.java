package io.aetherdb.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

@SuppressWarnings("preview")
class NativeMemoryTest {
    @Test
    void budgetRegionLifecycleAndAllocatorAccountingAreExact() {
        long capacity = RegionConfig.MIN_CAPACITY_BYTES;
        NativeMemoryBudget budget = new NativeMemoryBudget(capacity);
        NativeRegion region = new DefaultNativeRegionFactory(budget).create(capacity, "test");
        assertThat(budget.reservedBytes()).isEqualTo(capacity);
        NativeAllocator.Allocation first = region.allocator().tryAllocate(3, 8);
        NativeAllocator.Allocation second = region.allocator().tryAllocate(8, 16);
        assertThat(first.offset()).isEqualTo(64);
        assertThat(second.offset()).isEqualTo(80);
        assertThat(region.allocator().allocatedPayloadBytes()).isEqualTo(11);
        assertThat(region.allocator().alignmentPaddingBytes()).isEqualTo(13);
        region.freeze();
        assertThatThrownBy(() -> region.allocator().tryAllocate(1, 1)).isInstanceOf(IllegalStateException.class);
        region.close();
        region.close();
        assertThat(budget.reservedBytes()).isZero();
        assertThat(budget.regionCount()).isZero();
    }

    @Test
    void concurrentReservationsNeverOverlap() {
        long capacity = RegionConfig.MIN_CAPACITY_BYTES;
        NativeRegion region = new DefaultNativeRegionFactory(new NativeMemoryBudget(capacity)).create(capacity, "concurrent");
        Set<Integer> offsets = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 10_000).parallel().forEach(ignored -> {
            NativeAllocator.Allocation allocation = region.allocator().tryAllocate(8, 8);
            assertThat(allocation.allocated()).isTrue();
            offsets.add(allocation.offset());
        });
        assertThat(offsets).hasSize(10_000);
        assertThat(new HashSet<>(offsets)).hasSize(10_000);
        region.freeze();
        region.close();
    }

    @Test
    void recordBytesRoundTripAndRemainOwnedByRegion() {
        long capacity = RegionConfig.MIN_CAPACITY_BYTES;
        NativeMemoryBudget budget = new NativeMemoryBudget(capacity);
        NativeRegion region = new DefaultNativeRegionFactory(budget).create(capacity, "records");
        NativeRecordWriter writer = new NativeRecordWriter(region);
        byte[] key = {(byte) 0x80, 1};
        byte[] value = {};
        NativeAllocator.Allocation valueRecord = writer.writeValue(key, value, 7);
        NativeAllocator.Allocation tombstone = writer.writeTombstone(new byte[] {(byte) 0xff}, 8);
        key[0] = 0;
        NativeRecordReader reader = new NativeRecordReader(region);
        NativeRecordView valueView = reader.openChecked(valueRecord.offset());
        assertThat(valueView.copyKey()).containsExactly((byte) 0x80, (byte) 1);
        assertThat(valueView.copyValue()).isEmpty();
        assertThat(valueView.sequence()).isEqualTo(7);
        assertThat(valueView.isTombstone()).isFalse();
        assertThat(reader.openChecked(tombstone.offset()).isTombstone()).isTrue();
        assertThat(valueView.compareKey(new byte[] {(byte) 0x81})).isNegative();
        region.freeze();
        assertThat(valueView.copyKey()).hasSize(2);
        region.close();
        assertThatThrownBy(valueView::copyKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkedReaderRejectsCorruption() {
        long capacity = RegionConfig.MIN_CAPACITY_BYTES;
        NativeRegion region = new DefaultNativeRegionFactory(new NativeMemoryBudget(capacity)).create(capacity, "corrupt");
        NativeAllocator.Allocation allocation = new NativeRecordWriter(region).writeValue(new byte[] {1}, new byte[] {2}, 1);
        NativeAccess.setByte(region.rootSegment(), allocation.offset() + NativeRecordFormatV1.FLAGS_OFFSET, (byte) 1);
        assertThatThrownBy(() -> new NativeRecordReader(region).openChecked(allocation.offset()))
                .isInstanceOf(NativeRecordCorruptionException.class);
        region.freeze();
        region.close();
    }
}
