package io.aetherdb.memtable.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** TreeMap reference MVCC store retaining every inserted version. */
public final class VersionedKeyValueStore {
    private final NavigableMap<ByteKey, NavigableMap<Long, VersionedRecord>> entries = new TreeMap<>();

    public void insert(ByteKey key, VersionedRecord record) {
        NavigableMap<Long, VersionedRecord> versions =
                entries.computeIfAbsent(key, ignored -> new TreeMap<>(Collections.reverseOrder()));
        if (versions.putIfAbsent(record.sequence(), record) != null) {
            throw new IllegalStateException("duplicate sequence " + record.sequence());
        }
    }

    public VersionedRecord resolve(ByteKey key, long visibleSequence) {
        NavigableMap<Long, VersionedRecord> versions = entries.get(key);
        if (versions == null) {
            return null;
        }
        for (Map.Entry<Long, VersionedRecord> version : versions.entrySet()) {
            if (version.getKey() <= visibleSequence) {
                return version.getValue();
            }
        }
        return null;
    }

    public List<VisibleEntry> scan(ByteKey startInclusive, ByteKey endExclusive, long visibleSequence) {
        List<VisibleEntry> results = new ArrayList<>();
        for (ByteKey key : entries.subMap(startInclusive, true, endExclusive, false).keySet()) {
            VersionedRecord record = resolve(key, visibleSequence);
            if (record != null && record.type() == VersionedRecord.Type.VALUE) {
                results.add(new VisibleEntry(key.copyBytes(), record.copyValue()));
            }
        }
        return results;
    }

    public List<VisibleEntry> scanAll(long visibleSequence) {
        List<VisibleEntry> results = new ArrayList<>();
        for (ByteKey key : entries.keySet()) {
            VersionedRecord record = resolve(key, visibleSequence);
            if (record != null && record.type() == VersionedRecord.Type.VALUE)
                results.add(new VisibleEntry(key.copyBytes(), record.copyValue()));
        }
        return results;
    }

    public int versionCount(ByteKey key) {
        NavigableMap<Long, VersionedRecord> versions = entries.get(key);
        return versions == null ? 0 : versions.size();
    }

    public record VisibleEntry(byte[] key, byte[] value) {
        public VisibleEntry {
            key = key.clone();
            value = value.clone();
        }

        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
}
