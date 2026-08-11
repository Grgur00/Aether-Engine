package io.aetherdb.lsm.read;

/** A topology component whose lifetime can be retained by a read view. */
public interface RetainedSource {
    /** Acquires one lifetime reference. */
    void retain();

    /** Releases one lifetime reference. */
    void release();
}
