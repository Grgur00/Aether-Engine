package io.aetherdb.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Process-lifetime exclusive database LOCK file lease. The file remains after close. */
public final class DatabaseLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private DatabaseLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the database's non-blocking, process-exclusive lock lease.
     *
     * @param databaseRoot validated database directory
     * @return acquired lock that must be closed
     * @throws IOException if the directory is unsafe or another process holds the lock
     */
    public static DatabaseLock acquire(Path databaseRoot) throws IOException {
        Path root = PathSecurityValidator.validateRoot(databaseRoot, true);
        Path lockPath = root.resolve("LOCK");
        FileChannel channel =
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException unavailable) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new IOException("database lock is already held");
            }
            return new DatabaseLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel.isOpen()) channel.close();
            throw failure;
        }
    }

    /** Releases the operating-system lock and closes its channel. */
    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
