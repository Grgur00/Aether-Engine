package io.aetherdb.training.cache;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/** Loopback TLS daemon with optional mutual certificate authentication. */
public final class TrainingCacheTlsDaemon implements AutoCloseable {
    private final TrainingCache cache;
    private final SSLServerSocket server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(128);
    private final TrainingCacheProtocolMetrics protocolMetrics = new TrainingCacheProtocolMetrics();

    public TrainingCacheTlsDaemon(java.nio.file.Path directory, int port, long maximumBytes,
            SSLContext context, boolean requireClientAuthentication) throws IOException {
        cache = TrainingCache.open(Objects.requireNonNull(directory, "directory"), maximumBytes);
        SSLServerSocketFactory factory = Objects.requireNonNull(context, "context").getServerSocketFactory();
        server = (SSLServerSocket) factory.createServerSocket(port, 128, InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(requireClientAuthentication);
        workers.submit(this::acceptLoop);
    }

    public int port() { return server.getLocalPort(); }
    public java.util.Map<String, Long> protocolMetrics() { return protocolMetrics.snapshot(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                SSLSocket socket = (SSLSocket) server.accept();
                workers.submit(() -> serve(socket));
            } catch (IOException ignored) { if (!server.isClosed()) throw new IllegalStateException(ignored); }
        }
    }

    private void serve(SSLSocket socket) {
        try (socket) {
            socket.startHandshake();
            TrainingCacheProtocol.serve(socket.getInputStream(), socket.getOutputStream(), cache, permits, protocolMetrics);
        } catch (Exception ignored) { }
    }

    @Override public void close() throws IOException {
        server.close();
        workers.shutdownNow();
        cache.close();
    }
}
