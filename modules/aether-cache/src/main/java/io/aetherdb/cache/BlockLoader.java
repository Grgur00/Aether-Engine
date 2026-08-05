package io.aetherdb.cache;

/** Loads and checksum-verifies an immutable raw block before returning it. */
@FunctionalInterface
public interface BlockLoader {
    byte[] load();
}
