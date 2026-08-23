package io.aetherdb.io;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Verified portable backup archive contents.
 *
 * @param manifest decoded backup manifest
 * @param objects object payloads keyed by manifest object path
 */
public record BackupArchiveContents(BackupManifestV1 manifest, Map<String, byte[]> objects) {
    public BackupArchiveContents {
        Objects.requireNonNull(manifest, "manifest");
        objects = deepCopy(objects);
    }

    @Override
    public Map<String, byte[]> objects() {
        return deepCopy(objects);
    }

    public byte[] objectBytes(String path) {
        byte[] bytes = objects.get(path);
        if (bytes == null) throw new IllegalArgumentException("unknown backup object path");
        return bytes.clone();
    }

    private static Map<String, byte[]> deepCopy(Map<String, byte[]> source) {
        Objects.requireNonNull(source, "objects");
        Map<String, byte[]> copy = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null)
                throw new IllegalArgumentException("backup archive objects cannot contain nulls");
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Map.copyOf(copy);
    }
}
