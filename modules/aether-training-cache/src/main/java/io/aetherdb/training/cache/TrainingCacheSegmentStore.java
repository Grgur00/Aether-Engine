package io.aetherdb.training.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.Objects;

final class TrainingCacheSegmentStore {
    private final Path directory;

    TrainingCacheSegmentStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").resolve("segments");
        try {
            Files.createDirectories(this.directory);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot create cache segments", failure);
        }
    }

    void publish(String name, byte[] payload, boolean durable) {
        Path target = directory.resolve(name);
        Path temporary = directory.resolve(name + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(payload);
                TrainingCacheRequestTrace.measureIo("segmentWrite", () -> {
                    while (buffer.hasRemaining()) channel.write(buffer);
                    return null;
                });
                if (durable) TrainingCacheRequestTrace.measureIo("segmentFileSync", () -> { channel.force(true); return null; });
            }
            TrainingCacheFaultHooks.reach("after-data-fsync");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic segment publication is required", unsupported);
            }
            // Directory fsync is supported on the evaluated Linux filesystem.
            // Windows only gets the process-crash contract; report that limit.
            if (durable && !System.getProperty("os.name").toLowerCase().contains("win")) {
                try (FileChannel channel = FileChannel.open(directory)) {
                    TrainingCacheRequestTrace.measureIo("segmentDirectorySync", () -> { channel.force(true); return null; });
                }
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("cannot publish cache segment", failure);
        }
    }

    byte[] read(String name) {
        try {
            return TrainingCacheRequestTrace.measureIo("segmentRead", () -> Files.readAllBytes(directory.resolve(name)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("cache segment unavailable", failure);
        }
    }

    MappedByteBuffer mapReadOnly(String name) {
        if (System.getProperty("os.name").toLowerCase().contains("win"))
            throw new IllegalArgumentException("mapped segments are unavailable on Windows");
        try (FileChannel channel = FileChannel.open(directory.resolve(name))) {
            return channel.map(MapMode.READ_ONLY, 0, channel.size());
        } catch (IOException failure) {
            throw new IllegalArgumentException("cache segment unavailable", failure);
        }
    }

    long size(String name) {
        try {
            return Files.size(directory.resolve(name));
        } catch (IOException failure) {
            throw new IllegalArgumentException("cache segment unavailable", failure);
        }
    }

    void delete(String name) {
        try {
            Files.deleteIfExists(directory.resolve(name));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot delete cache segment", failure);
        }
    }
}
