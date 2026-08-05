package io.aetherdb.api;

import io.aetherdb.api.exceptions.AetherClosedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ordered, defensively copied, bounded, one-shot mutation batch. */
public final class WriteBatch implements AutoCloseable {
    public static final int MAX_OPERATIONS = 10_000;
    public static final long MAX_ENCODED_BYTES = 32L * 1024 * 1024;
    public static final int MAX_KEY_BYTES = 65_536;
    public static final int MAX_VALUE_BYTES = 16 * 1024 * 1024;
    private static final long BATCH_HEADER_BYTES = 24;
    private static final long OPERATION_HEADER_BYTES = 12;

    public enum State { OPEN, SEALED, SUBMITTED, SUCCEEDED, FAILED, INDETERMINATE, CLOSED }

    private final List<Mutation> mutations = new ArrayList<>();
    private State state = State.OPEN;
    private long encodedSizeBytes;

    public WriteBatch put(byte[] key, byte[] value) {
        ensureOpen(); validateKey(key); validateValue(value);
        addSize(key.length, value.length);
        mutations.add(new Put(key.clone(), value.clone()));
        return this;
    }

    public WriteBatch delete(byte[] key) {
        ensureOpen(); validateKey(key);
        addSize(key.length, 0);
        mutations.add(new Delete(key.clone()));
        return this;
    }

    public int operationCount() { ensureReadable(); return mutations.size(); }
    public int size() { return operationCount(); }
    public boolean isEmpty() { ensureReadable(); return mutations.isEmpty(); }
    public long encodedSizeBytes() { ensureReadable(); return mutations.isEmpty() ? 0 : encodedSizeBytes; }
    public State state() { return state; }

    /** Internal cross-module view; mutation byte accessors return copies. */
    public List<Mutation> mutations() { ensureReadable(); return Collections.unmodifiableList(mutations); }

    /** Claims this batch for exactly one submission. */
    public void sealForSubmission() {
        ensureOpen(); state = State.SEALED;
    }
    public void markSubmitted() { requireState(State.SEALED); state = State.SUBMITTED; }
    public void markSucceeded() {
        if (state != State.SEALED && state != State.SUBMITTED) throw new IllegalStateException("batch is not being submitted");
        state = State.SUCCEEDED;
    }
    public void markFailed() {
        if (state != State.SEALED && state != State.SUBMITTED) throw new IllegalStateException("batch is not being submitted");
        state = State.FAILED;
    }
    public void markIndeterminate() {
        if (state != State.SUBMITTED) throw new IllegalStateException("batch was not submitted");
        state = State.INDETERMINATE;
    }

    /** Compatibility alias for the semantic reference engine. */
    public void markCommitted() { markSucceeded(); }
    public boolean isClosed() { return state != State.OPEN; }

    @Override public void close() { if (state == State.OPEN) state = State.CLOSED; }

    private void addSize(int keyLength, int valueLength) {
        if (mutations.size() >= MAX_OPERATIONS) throw new IllegalArgumentException("write batch exceeds 10,000 operations");
        long base = mutations.isEmpty() ? BATCH_HEADER_BYTES : encodedSizeBytes;
        long addition = Math.addExact(OPERATION_HEADER_BYTES, Math.addExact(keyLength, valueLength));
        long candidate = Math.addExact(base, addition);
        if (candidate > MAX_ENCODED_BYTES) throw new IllegalArgumentException("write batch exceeds 32 MiB encoded size");
        encodedSizeBytes = candidate;
    }
    private static void validateKey(byte[] key) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (key.length > MAX_KEY_BYTES) throw new IllegalArgumentException("key exceeds 65,536 bytes");
    }
    private static void validateValue(byte[] value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        if (value.length > MAX_VALUE_BYTES) throw new IllegalArgumentException("value exceeds 16 MiB");
    }
    private void ensureOpen() { if (state != State.OPEN) throw new AetherClosedException("write batch is not open: " + state); }
    private void ensureReadable() { if (state == State.CLOSED) throw new AetherClosedException("write batch is closed"); }
    private void requireState(State expected) { if (state != expected) throw new IllegalStateException("expected " + expected + " but was " + state); }

    public sealed interface Mutation permits Put, Delete { byte[] key(); }
    public static final class Put implements Mutation {
        private final byte[] key; private final byte[] value;
        private Put(byte[] key, byte[] value) { this.key = key; this.value = value; }
        @Override public byte[] key() { return Arrays.copyOf(key, key.length); }
        public byte[] value() { return Arrays.copyOf(value, value.length); }
    }
    public static final class Delete implements Mutation {
        private final byte[] key;
        private Delete(byte[] key) { this.key = key; }
        @Override public byte[] key() { return Arrays.copyOf(key, key.length); }
    }
}
