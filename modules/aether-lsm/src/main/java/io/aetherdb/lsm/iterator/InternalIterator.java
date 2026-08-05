package io.aetherdb.lsm.iterator;

/** Forward-only iterator over strictly ordered internal entries. */
public interface InternalIterator extends AutoCloseable {
    boolean next();
    InternalEntry current();
    @Override void close();
}
