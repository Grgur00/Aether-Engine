package io.aetherdb.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("preview")
/** Native memory region backed by a Foreign Function and Memory API arena. */
final class FfmNativeRegion implements NativeRegion {
    private final String ownerId;
    private final long capacity;
    private final Arena arena;
    private final MemorySegment root;
    private final NativeMemoryBudget budget;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicBoolean budgetReleased = new AtomicBoolean();
    private final MonotonicNativeAllocator allocator;

    FfmNativeRegion(String ownerId, long capacity, Arena arena, MemorySegment root, NativeMemoryBudget budget) {
        this.ownerId = ownerId;
        this.capacity = capacity;
        this.arena = arena;
        this.root = root;
        this.budget = budget;
        allocator = new MonotonicNativeAllocator(this);
    }

    @Override public long capacityBytes() { return capacity; }
    @Override public State state() { return state.get(); }
    @Override public NativeAllocator allocator() { ensureAlive(); return allocator; }
    @Override public MemorySegment rootSegment() { ensureAlive(); return root; }

    @Override
    public void freeze() {
        State current = state.get();
        if (current == State.CLOSED) throw new IllegalStateException("region is closed");
        state.compareAndSet(State.OPEN, State.FROZEN);
    }

    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) return;
        if (current != State.FROZEN) throw new IllegalStateException("region must be frozen before close: " + ownerId);
        if (state.compareAndSet(State.FROZEN, State.CLOSED)) {
            try { arena.close(); }
            finally {
                if (budgetReleased.compareAndSet(false, true)) budget.release(capacity);
            }
        }
    }

    void ensureOpenForAllocation() {
        if (state.get() != State.OPEN) throw new IllegalStateException("region is not open");
    }

    void ensureAlive() {
        if (state.get() == State.CLOSED) throw new IllegalStateException("region is closed");
    }
}
