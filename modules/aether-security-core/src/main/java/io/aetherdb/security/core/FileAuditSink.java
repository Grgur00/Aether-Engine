package io.aetherdb.security.core;

import io.aetherdb.security.api.AuditEvent;
import io.aetherdb.security.api.AuditSink;
import io.aetherdb.security.api.AuditUnavailableException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Append-only JSON-lines audit sink backed by a local file. */
public final class FileAuditSink implements AuditSink, AutoCloseable {
    private final FileChannel channel;
    private final boolean forceOnRecord;

    public FileAuditSink(Path path, boolean forceOnRecord) throws AuditUnavailableException {
        Objects.requireNonNull(path, "path");
        this.forceOnRecord = forceOnRecord;
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            channel =
                    FileChannel.open(
                            path,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new AuditUnavailableException("cannot open audit file", failure);
        }
    }

    @Override
    public synchronized void record(AuditEvent event) throws AuditUnavailableException {
        byte[] encoded = (AuditJsonFormatter.format(event) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) channel.write(buffer);
            if (forceOnRecord) channel.force(false);
        } catch (IOException failure) {
            throw new AuditUnavailableException("cannot append audit event", failure);
        }
    }

    @Override
    public synchronized void close() throws AuditUnavailableException {
        try {
            channel.close();
        } catch (IOException failure) {
            throw new AuditUnavailableException("cannot close audit file", failure);
        }
    }
}
