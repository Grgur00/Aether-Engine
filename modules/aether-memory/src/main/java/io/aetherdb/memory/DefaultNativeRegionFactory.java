package io.aetherdb.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** FFM shared-arena region factory with exact budget rollback. */
@SuppressWarnings("preview")
public final class DefaultNativeRegionFactory implements NativeRegionFactory {
    private final NativeMemoryBudget budget;

    public DefaultNativeRegionFactory(NativeMemoryBudget budget) { this.budget = budget; }

    @Override
    public NativeRegion create(long capacityBytes, String ownerId) {
        RegionConfig.validateCapacity(capacityBytes);
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (!budget.tryReserve(capacityBytes)) throw new NativeAllocationException("native budget exhausted");
        Arena arena = null;
        try {
            arena = Arena.ofShared();
            MemorySegment root = arena.allocate(capacityBytes, RegionConfig.DEFAULT_ALIGNMENT);
            root.fill((byte) 0);
            return new FfmNativeRegion(ownerId, capacityBytes, arena, root, budget);
        } catch (RuntimeException | OutOfMemoryError failure) {
            if (arena != null) {
                try { arena.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            budget.release(capacityBytes);
            if (failure instanceof Error error) throw error;
            throw new NativeAllocationException("native allocation failed", failure);
        }
    }
}
