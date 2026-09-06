package io.aetherdb.training.cache;

/** Durability contract for cached training transformations. */
public enum TrainingCacheDurability {
    EPHEMERAL,
    RECOVERABLE,
    DURABLE
}
