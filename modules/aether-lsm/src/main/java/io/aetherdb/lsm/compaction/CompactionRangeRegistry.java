package io.aetherdb.lsm.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Prevents overlapping compaction jobs and releases registrations idempotently. */
public final class CompactionRangeRegistry {
    private final List<Range> active = new ArrayList<>();
    public synchronized Registration register(int inputLevel, int outputLevel, byte[] smallest, byte[] largest) {
        Range proposed = new Range(inputLevel, outputLevel, smallest.clone(), largest.clone());
        if (Arrays.compareUnsigned(smallest, largest) > 0) throw new IllegalArgumentException("reversed range");
        if (active.stream().anyMatch(proposed::conflicts)) throw new IllegalStateException("compaction range conflicts with active job");
        active.add(proposed); return new Registration(this, proposed);
    }
    public synchronized int activeCount() { return active.size(); }
    private synchronized void release(Range range) { if (!active.remove(range)) throw new IllegalStateException("range registration missing"); }
    private record Range(int inputLevel, int outputLevel, byte[] smallest, byte[] largest) {
        boolean conflicts(Range other) {
            boolean sharedLevel = inputLevel == other.inputLevel || inputLevel == other.outputLevel
                    || outputLevel == other.inputLevel || outputLevel == other.outputLevel;
            return sharedLevel && Arrays.compareUnsigned(smallest, other.largest) <= 0
                    && Arrays.compareUnsigned(other.smallest, largest) <= 0;
        }
    }
    public static final class Registration implements AutoCloseable {
        private CompactionRangeRegistry owner; private final Range range;
        Registration(CompactionRangeRegistry owner, Range range) { this.owner = owner; this.range = range; }
        @Override public synchronized void close() { if (owner != null) { CompactionRangeRegistry current = owner; owner = null; current.release(range); } }
    }
}
