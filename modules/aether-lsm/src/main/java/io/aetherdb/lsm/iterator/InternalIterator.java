package io.aetherdb.lsm.iterator;

/** Forward-only iterator over strictly ordered internal entries. */
public interface InternalIterator extends AutoCloseable {
    /**
     * Advances to the next internal entry.
     *
     * @return whether positioned on an entry
     */
    boolean next();

    /**
     * Returns the current entry.
     *
     * @return positioned entry
     */
    InternalEntry current();

    /** Releases iterator resources. */
    @Override
    void close();
}
