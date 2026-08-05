package io.aetherdb.cache;

/** Loads and checksum-verifies an immutable raw block before returning it. */
@FunctionalInterface
public interface BlockLoader {
    /** Loads and verifies one immutable block.
     * @return newly loaded block bytes */
    byte[] load();
}
