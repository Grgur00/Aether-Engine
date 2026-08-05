package io.aetherdb.lsm.read;

/** A topology component whose lifetime can be retained by a read view. */
public interface RetainedSource {
    void retain();
    void release();
}
