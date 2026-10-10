package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Sequential DATA-block cursor. Values are skipped, never materialized. */
public final class RestartBlockScanner {
    private static final int MAX_KEY_BYTES = 65_536 + 9;
    private final byte[] raw;
    private final int limit;
    private final ByteBuffer restarts;
    private final int restartCount;
    private int restartIndex;
    private byte[] key = new byte[128];
    private int keyLength;
    private int cursor;
    private int valueOffset;
    private int valueLength;
    private boolean valid;

    /** Borrows a checksummed payload for the lifetime of this cursor. */
    public RestartBlockScanner(byte[] raw) {
        this.raw = raw;
        limit = RestartBlock.entryLimit(raw);
        restarts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        restartCount = restarts.getInt(raw.length - 4);
        if (restarts.getInt(limit) != 0) throw corrupt("first restart is not zero");
    }

    /** Advances one entry with bounded key scratch and no value allocation. */
    public boolean next() {
        valid = false;
        if (cursor == limit) return false;
        int start = cursor;
        var shared = Varint32.decode(raw, cursor, limit);
        cursor += shared.bytes();
        var suffix = Varint32.decode(raw, cursor, limit);
        cursor += suffix.bytes();
        var value = Varint32.decode(raw, cursor, limit);
        cursor += value.bytes();
        long length = (long) shared.value() + suffix.value();
        long end = (long) cursor + suffix.value() + value.value();
        if (shared.value() > keyLength || length > MAX_KEY_BYTES || end > limit)
            throw corrupt("entry exceeds block or internal-key limit");
        if (restartIndex < restartCount) {
            int restart = restarts.getInt(limit + restartIndex * 4);
            if (restart == start) {
                if (shared.value() != 0) throw corrupt("shared prefix at restart");
                restartIndex++;
            }
            if (restartIndex < restartCount
                    && restarts.getInt(limit + restartIndex * 4) < end)
                throw corrupt("restart is not an entry boundary");
        }
        int required = (int) length;
        if (required > key.length)
            key = Arrays.copyOf(key, Math.min(MAX_KEY_BYTES, Math.max(required, key.length * 2)));
        System.arraycopy(raw, cursor, key, shared.value(), suffix.value());
        keyLength = required;
        valueOffset = cursor + suffix.value();
        valueLength = value.value();
        cursor = (int) end;
        valid = true;
        return true;
    }

    /** Borrowed scratch, valid only until next(); callers must not modify it. */
    public byte[] keyBuffer() { requireValid(); return key; }
    public int keyLength() { requireValid(); return keyLength; }
    public int valueOffset() { requireValid(); return valueOffset; }
    public int valueLength() { requireValid(); return valueLength; }

    private void requireValid() {
        if (!valid) throw new IllegalStateException("cursor has no entry");
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
