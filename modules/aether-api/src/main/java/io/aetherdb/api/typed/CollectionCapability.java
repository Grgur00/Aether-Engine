package io.aetherdb.api.typed;

/** Operations supported by a typed collection definition. */
public enum CollectionCapability {
    /** Read one value by its complete key. */
    POINT_READ,
    /** Insert, replace, or delete one value by its complete key. */
    POINT_WRITE,
    /** Iterate keys in codec-defined order. */
    RANGE_SCAN,
    /** Read the collection through a stable database snapshot. */
    SNAPSHOT_READ
}
