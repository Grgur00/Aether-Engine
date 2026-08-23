package io.aetherdb.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable backup archive v1 writer/reader with manifest-driven object verification. */
public final class BackupArchiveV1 {
    public static final String MANIFEST_ENTRY = "AETHER-BACKUP-MANIFEST.v1";

    private BackupArchiveV1() {}

    /**
     * Writes a portable archive containing the manifest and all objects it references.
     *
     * @param output destination stream, left open
     * @param manifest backup manifest
     * @param objects object bytes keyed by manifest path
     */
    public static void write(
            OutputStream output, BackupManifestV1 manifest, Map<String, byte[]> objects)
            throws IOException {
        Objects.requireNonNull(output, "output");
        validateObjects(manifest, objects);
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            putEntry(archive, MANIFEST_ENTRY, manifest.encode());
            for (BackupManifestObject object : manifest.objects()) {
                putEntry(archive, object.path(), objects.get(object.path()));
            }
            archive.finish();
        }
    }

    /**
     * Reads and verifies a portable archive.
     *
     * @param input source stream, left open
     * @return decoded manifest and verified object bytes
     */
    public static BackupArchiveContents read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] manifestBytes = null;
        Map<String, byte[]> entries = new HashMap<>();
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.isDirectory()) throw new IOException("backup archive contains directory");
                String name = entry.getName();
                if (!names.add(name)) throw new IOException("duplicate backup archive entry");
                byte[] bytes = readAll(archive);
                if (MANIFEST_ENTRY.equals(name)) manifestBytes = bytes;
                else entries.put(name, bytes);
                archive.closeEntry();
            }
        }
        if (manifestBytes == null) throw new IOException("backup archive manifest missing");
        BackupManifestV1 manifest;
        try {
            manifest = BackupManifestV1.decode(manifestBytes);
            validateObjects(manifest, entries);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid backup archive: " + failure.getMessage(), failure);
        }
        return new BackupArchiveContents(manifest, entries);
    }

    private static void putEntry(ZipOutputStream archive, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        archive.putNextEntry(entry);
        archive.write(bytes);
        archive.closeEntry();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        return output.toByteArray();
    }

    private static void validateObjects(BackupManifestV1 manifest, Map<String, byte[]> objects) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(objects, "objects");
        Set<String> expected = new HashSet<>();
        for (BackupManifestObject object : manifest.objects()) {
            expected.add(object.path());
            byte[] bytes = objects.get(object.path());
            if (bytes == null) throw new IllegalArgumentException("backup object missing");
            if (bytes.length != object.length())
                throw new IllegalArgumentException("backup object length mismatch");
            if (!object.checksumEquals(sha256(bytes)))
                throw new IllegalArgumentException("backup object checksum mismatch");
        }
        if (!objects.keySet().equals(expected))
            throw new IllegalArgumentException("backup archive object inventory mismatch");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance(BackupManifestV1.HASH_SHA256).digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
