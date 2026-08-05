package io.aetherdb.memory;

/** Creates budgeted shared native regions. */
public interface NativeRegionFactory {
    NativeRegion create(long capacityBytes, String ownerId);
}
