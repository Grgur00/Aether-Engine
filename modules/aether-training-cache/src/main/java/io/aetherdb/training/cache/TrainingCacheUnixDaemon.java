package io.aetherdb.training.cache;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Unix-domain-socket daemon for local training workers on supported operating systems. */
public final class TrainingCacheUnixDaemon implements AutoCloseable {
    private final Path socketPath;
    private final ServerSocketChannel server;
    private final TrainingCache cache;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(128);
    private final TrainingCacheProtocolMetrics protocolMetrics = new TrainingCacheProtocolMetrics();

    public TrainingCacheUnixDaemon(Path directory, Path socketPath, long maximumBytes) throws IOException {
        this.socketPath = socketPath.toAbsolutePath().normalize();
        Files.deleteIfExists(this.socketPath);
        cache = TrainingCache.open(directory, maximumBytes);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(this.socketPath));
        workers.submit(this::acceptLoop);
    }

    public Path socketPath() { return socketPath; }
    public java.util.Map<String, Long> protocolMetrics() { return protocolMetrics.snapshot(); }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || arguments.length > 3)
            throw new IllegalArgumentException("usage: <directory> <socketPath> [maximumBytes]");
        long maximum = arguments.length == 3 ? Long.parseLong(arguments[2]) : TrainingCache.DEFAULT_MAX_BYTES;
        try (TrainingCacheUnixDaemon daemon = new TrainingCacheUnixDaemon(
                Path.of(arguments[0]), Path.of(arguments[1]), maximum)) {
            System.out.println(daemon.socketPath());
            Thread.currentThread().join();
        }
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            try {
                SocketChannel socket = server.accept();
                workers.submit(() -> serve(socket));
            } catch (IOException ignored) { if (server.isOpen()) throw new IllegalStateException(ignored); }
        }
    }

    private void serve(SocketChannel socket) {
        try (socket) {
            TrainingCacheProtocol.serve(Channels.newInputStream(socket), Channels.newOutputStream(socket), cache, permits, protocolMetrics);
        } catch (Exception ignored) { }
    }

    @Override public void close() throws IOException {
        server.close();
        workers.shutdownNow();
        cache.close();
        Files.deleteIfExists(socketPath);
    }
}
