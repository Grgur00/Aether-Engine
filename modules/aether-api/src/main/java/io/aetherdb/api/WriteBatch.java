package io.aetherdb.api;

import io.aetherdb.api.exceptions.AetherClosedException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ordered, defensively copied, bounded, one-shot mutation batch. */
public final class WriteBatch implements AutoCloseable {
    /** Maximum mutations accepted by one batch. */
    public static final int MAX_OPERATIONS = 10_000;

    /** Maximum encoded batch size in bytes. */
    public static final long MAX_ENCODED_BYTES = 32L * 1024 * 1024;

    /** Maximum key length in bytes. */
    public static final int MAX_KEY_BYTES = 65_536;

    /** Maximum value length in bytes. */
    public static final int MAX_VALUE_BYTES = 16 * 1024 * 1024;

    private static final long BATCH_HEADER_BYTES = 24;
    private static final long OPERATION_HEADER_BYTES = 12;

    /** Lifecycle state of a one-shot batch. */
    public enum State {
        /** Accepting mutations. */
        OPEN,
        /** Claimed by a writer but not yet submitted. */
        SEALED,
        /** Submitted past the implementation's uncertainty boundary. */
        SUBMITTED,
        /** Applied successfully. */
        SUCCEEDED,
        /** Failed with a definite non-commit outcome. */
        FAILED,
        /** Final commit outcome is unknown. */
        INDETERMINATE,
        /** Closed before submission. */
        CLOSED
    }

    private final List<Mutation> mutations = new ArrayList<>();
    private State state = State.OPEN;
    private long encodedSizeBytes;

    /** Creates an empty, open mutation batch. */
    public WriteBatch() {}

    /**
     * Adds an insert-or-replace mutation.
     *
     * @param key key bytes copied by the batch
     * @param value value bytes copied by the batch
     * @return this batch
     */
    public WriteBatch put(byte[] key, byte[] value) {
        ensureOpen();
        validateKey(key);
        validateValue(value);
        addSize(key.length, value.length);
        mutations.add(new Put(key.clone(), value.clone()));
        return this;
    }

    /**
     * Adds a deletion mutation.
     *
     * @param key key bytes copied by the batch
     * @return this batch
     */
    public WriteBatch delete(byte[] key) {
        ensureOpen();
        validateKey(key);
        addSize(key.length, 0);
        mutations.add(new Delete(key.clone()));
        return this;
    }

    /**
     * Returns the mutation count.
     *
     * @return accumulated operation count
     */
    public int operationCount() {
        ensureReadable();
        return mutations.size();
    }

    /**
     * Alias for {@link #operationCount()}.
     *
     * @return accumulated operation count
     */
    public int size() {
        return operationCount();
    }

    /**
     * Reports whether no mutations have been added.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        ensureReadable();
        return mutations.isEmpty();
    }

    /**
     * Returns the encoded-size estimate.
     *
     * @return zero when empty, otherwise bounded encoded bytes
     */
    public long encodedSizeBytes() {
        ensureReadable();
        return mutations.isEmpty() ? 0 : encodedSizeBytes;
    }

    /**
     * Returns the lifecycle state.
     *
     * @return current batch state
     */
    public State state() {
        return state;
    }

    /**
     * Internal cross-module view; mutation byte accessors return copies.
     *
     * @return unmodifiable ordered mutation list
     */
    public List<Mutation> mutations() {
        ensureReadable();
        return Collections.unmodifiableList(mutations);
    }

    /** Claims this batch for exactly one submission. */
    public void sealForSubmission() {
        ensureOpen();
        state = State.SEALED;
    }

    /** Records that the batch crossed the submission uncertainty boundary. */
    public void markSubmitted() {
        requireState(State.SEALED);
        state = State.SUBMITTED;
    }

    /** Records a successful terminal outcome. */
    public void markSucceeded() {
        if (state != State.SEALED && state != State.SUBMITTED)
            throw new IllegalStateException("batch is not being submitted");
        state = State.SUCCEEDED;
    }

    /** Records a definite failed terminal outcome. */
    public void markFailed() {
        if (state != State.SEALED && state != State.SUBMITTED)
            throw new IllegalStateException("batch is not being submitted");
        state = State.FAILED;
    }

    /** Records that the final commit outcome is unknown. */
    public void markIndeterminate() {
        if (state != State.SUBMITTED) throw new IllegalStateException("batch was not submitted");
        state = State.INDETERMINATE;
    }

    /** Compatibility alias for the semantic reference engine. */
    public void markCommitted() {
        markSucceeded();
    }

    /**
     * Reports whether the batch no longer accepts mutations.
     *
     * @return {@code true} unless open
     */
    public boolean isClosed() {
        return state != State.OPEN;
    }

    /** Closes an open, unsubmitted batch. */
    @Override
    public void close() {
        if (state == State.OPEN) state = State.CLOSED;
    }

    private void addSize(int keyLength, int valueLength) {
        if (mutations.size() >= MAX_OPERATIONS)
            throw new IllegalArgumentException("write batch exceeds 10,000 operations");
        long base = mutations.isEmpty() ? BATCH_HEADER_BYTES : encodedSizeBytes;
        long addition =
                Math.addExact(OPERATION_HEADER_BYTES, Math.addExact(keyLength, valueLength));
        long candidate = Math.addExact(base, addition);
        if (candidate > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("write batch exceeds 32 MiB encoded size");
        encodedSizeBytes = candidate;
    }

    private static void validateKey(byte[] key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (key.length > MAX_KEY_BYTES)
            throw new IllegalArgumentException("key exceeds 65,536 bytes");
    }

    private static void validateValue(byte[] value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        if (value.length > MAX_VALUE_BYTES)
            throw new IllegalArgumentException("value exceeds 16 MiB");
    }

    private void ensureOpen() {
        if (state != State.OPEN)
            throw new AetherClosedException("write batch is not open: " + state);
    }

    private void ensureReadable() {
        if (state == State.CLOSED) throw new AetherClosedException("write batch is closed");
    }

    private void requireState(State expected) {
        if (state != expected)
            throw new IllegalStateException("expected " + expected + " but was " + state);
    }

    /** Immutable mutation exposed to database implementations. */
    public sealed interface Mutation permits Put, Delete {
        /**
         * Returns the mutation key.
         *
         * @return defensive key copy
         */
        byte[] key();
    }

    /** Immutable insert-or-replace mutation. */
    public static final class Put implements Mutation {
        private final byte[] key;
        private final byte[] value;

        private Put(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public byte[] key() {
            return Arrays.copyOf(key, key.length);
        }

        /**
         * Returns the value.
         *
         * @return defensive value copy
         */
        public byte[] value() {
            return Arrays.copyOf(value, value.length);
        }
    }

    /** Immutable deletion mutation. */
    public static final class Delete implements Mutation {
        private final byte[] key;

        private Delete(byte[] key) {
            this.key = key;
        }

        @Override
        public byte[] key() {
            return Arrays.copyOf(key, key.length);
        }
    }
}
