package io.aetherdb.training.cache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Loopback daemon exposing the training cache to local worker processes. */
public final class TrainingCacheDaemon implements AutoCloseable {
    public static final int DEFAULT_PORT = 0;
    private static final int VERSION = 1;
    private static final int GET = 1;
    private static final int PUT = 2;
    private static final int HIT = 1;
    private static final int MISS = 0;
    private static final int ERROR = 2;
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    private final TrainingCache cache;
    private final ServerSocket server;
    private final ExecutorService workers;
    private final Semaphore requestPermits = new Semaphore(128);
    private final TrainingCacheProtocolMetrics protocolMetrics = new TrainingCacheProtocolMetrics();

    public TrainingCacheDaemon(Path directory, int port, long maximumBytes) throws IOException {
        this(directory, port, maximumBytes, TrainingCacheDurability.RECOVERABLE);
    }

    public TrainingCacheDaemon(Path directory, int port, long maximumBytes,
            TrainingCacheDurability durability) throws IOException {
        cache = TrainingCache.open(Objects.requireNonNull(directory, "directory"), maximumBytes, durability);
        server = new ServerSocket(port, 128, InetAddress.getLoopbackAddress());
        workers = Executors.newCachedThreadPool();
        workers.submit(this::acceptLoop);
    }

    public int port() { return server.getLocalPort(); }
    public java.util.Map<String, Long> protocolMetrics() { return protocolMetrics.snapshot(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> serve(socket));
            }
            catch (IOException ignored) { if (!server.isClosed()) throw new IllegalStateException(ignored); }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            TrainingCacheProtocol.serve(socket.getInputStream(), socket.getOutputStream(), cache, requestPermits, protocolMetrics);
        }
        catch (Exception ignored) { }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 4) throw new IllegalArgumentException("usage: <directory> [port] [maximumBytes] [durability]");
        int port = arguments.length > 1 ? Integer.parseInt(arguments[1]) : DEFAULT_PORT;
        long maximum = arguments.length > 2 ? Long.parseLong(arguments[2]) : TrainingCache.DEFAULT_MAX_BYTES;
        TrainingCacheDurability durability = arguments.length > 3
                ? TrainingCacheDurability.valueOf(arguments[3]) : TrainingCacheDurability.RECOVERABLE;
        try (TrainingCacheDaemon daemon = new TrainingCacheDaemon(Path.of(arguments[0]), port, maximum, durability)) {
            System.out.println(daemon.port());
            Thread.currentThread().join();
        }
    }

    @Override public void close() throws IOException { server.close(); workers.shutdownNow(); cache.close(); }
}
