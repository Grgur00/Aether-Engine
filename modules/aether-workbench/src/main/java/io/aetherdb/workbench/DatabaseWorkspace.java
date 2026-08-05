package io.aetherdb.workbench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.result.LookupResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/** Text-oriented editing session backed by an Aether database. */
public final class DatabaseWorkspace implements AutoCloseable {
    private final AetherDatabase database;
    private final boolean closeDatabase;
    private final SortedSet<String> knownKeys = new TreeSet<>();

    public DatabaseWorkspace(AetherDatabase database) { this(database, true); }
    public DatabaseWorkspace(AetherDatabase database, boolean closeDatabase) {
        this.database = Objects.requireNonNull(database, "database"); this.closeDatabase = closeDatabase;
    }

    public void put(String key, String value) {
        validate(key, "key"); validate(value, "value");
        database.put(key.getBytes(UTF_8), value.getBytes(UTF_8));
        knownKeys.add(key);
    }

    public void edit(String originalKey, String newKey, String value) {
        validate(originalKey, "originalKey"); validate(newKey, "newKey"); validate(value, "value");
        if (!knownKeys.contains(originalKey)) throw new IllegalArgumentException("original key does not exist");
        if (!originalKey.equals(newKey) && knownKeys.contains(newKey)) throw new IllegalArgumentException("new key already exists");
        try (WriteBatch batch = new WriteBatch()) {
            if (!originalKey.equals(newKey)) batch.delete(originalKey.getBytes(UTF_8));
            batch.put(newKey.getBytes(UTF_8), value.getBytes(UTF_8));
            database.write(batch);
        }
        knownKeys.remove(originalKey); knownKeys.add(newKey);
    }

    public boolean delete(String key) {
        validate(key, "key");
        if (!knownKeys.remove(key)) return false;
        database.delete(key.getBytes(UTF_8));
        return true;
    }

    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        knownKeys.clear();
        try (AetherCursor cursor = database.scanAll()) {
            while (cursor.next()) {
                String key = new String(cursor.key(), UTF_8); byte[] valueBytes = cursor.value();
                knownKeys.add(key); rows.add(new Row(key, new String(valueBytes, UTF_8), valueBytes.length));
            }
        }
        return List.copyOf(rows);
    }

    public boolean contains(String key) { return knownKeys.contains(key); }
    public int size() { return knownKeys.size(); }
    @Override public void close() { if (closeDatabase) database.close(); knownKeys.clear(); }

    private static void validate(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }

    public record Row(String key, String value, int valueBytes) {
        /** Parent path used by the workbench to group slash-delimited application keys. */
        public String group() {
            int separator = key.lastIndexOf('/');
            return separator < 0 ? "(root)" : key.substring(0, separator);
        }

        /** Final path component shown as the editable field name. */
        public String field() {
            int separator = key.lastIndexOf('/');
            return separator < 0 ? key : key.substring(separator + 1);
        }
    }
}
