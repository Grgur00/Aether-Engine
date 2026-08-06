package io.aetherdb.rpc.transport;

import io.aetherdb.rpc.api.RpcBackpressureMode;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcCancellationToken;
import io.aetherdb.rpc.api.RpcClient;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcExecutionPolicy;
import io.aetherdb.rpc.api.RpcHandler;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponder;
import io.aetherdb.rpc.api.RpcResponse;
import io.aetherdb.rpc.api.RpcServer;
import io.aetherdb.rpc.api.RpcServerRequest;
import io.aetherdb.rpc.api.RpcStatus;
import io.aetherdb.rpc.api.RpcRetryClass;
import io.aetherdb.rpc.codec.RpcFrame;
import io.aetherdb.rpc.codec.RpcFrameDecoder;
import io.aetherdb.rpc.codec.RpcFrameCodecV1;
import io.aetherdb.rpc.codec.RpcFrameHeaderV1;
import io.aetherdb.rpc.codec.RpcFrameType;
import io.aetherdb.rpc.codec.RpcHelloV1;
import io.aetherdb.rpc.codec.RpcMessageAssembler;
import io.aetherdb.rpc.codec.RpcMessageFragmenter;
import io.aetherdb.rpc.codec.RpcProtocolException;
import io.aetherdb.rpc.codec.RpcStreamIdAllocator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit development-only RPC transport using mandatory v1 framing and HELLO identity checks.
 *
 * <p>Frame CRC, limits, multiplexing, cancellation, and deadlines remain active. This profile does
 * not provide confidentiality or certificate authentication and must not be used in production.</p>
 */
public final class PlaintextDevelopmentRpc {
    private static final int FRAME_BYTES = 1024 * 1024;
    private static final int MESSAGE_BYTES = 64 * 1024 * 1024;
    private static final int STREAMS = 1024;
    private static final int WINDOW_BYTES = 64 * 1024 * 1024;
    private static final int OUTBOUND_PERMITS = 64 * 1024;
    private static final int PERMIT_BYTES = 1024;

    private PlaintextDevelopmentRpc() {}

    /** Binds a development server; port zero requests an ephemeral operating-system port. */
    public static RpcServer bind(RpcIdentity identity, String host, int port) {
        return new DevelopmentServer(identity, host, port);
    }

    /** Creates a development client that owns and reuses multiplexed peer connections. */
    public static RpcClient client(RpcIdentity identity) { return new DevelopmentClient(identity); }

    private static RpcHelloV1 hello(RpcIdentity identity, RpcHelloV1.Role role) {
        long nonce; do nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong(); while (nonce == 0);
        return new RpcHelloV1(role, identity.clusterId(), identity.nodeId(), identity.sessionId(), nonce,
                FRAME_BYTES, MESSAGE_BYTES, STREAMS, WINDOW_BYTES, 30_000, 10_000, 0, 2, 0, 21);
    }

    private static RpcFrame helloFrame(RpcHelloV1 hello) {
        byte[] payload = hello.encode();
        return new RpcFrame(new RpcFrameHeaderV1(RpcFrameType.HELLO,
                RpcFrameHeaderV1.BEGIN | RpcFrameHeaderV1.END, 0, 0, payload.length,
                payload.length, 0, 0, 0, RpcFrameHeaderV1.ZERO_INVOCATION), payload);
    }

    private static void validatePeer(RpcIdentity local, RpcHelloV1 remote, RpcHelloV1.Role expectedRole,
                                     UUID expectedNode) {
        if (remote.role() != expectedRole || !remote.clusterId().equals(local.clusterId())
                || remote.nodeId().equals(local.nodeId())
                || expectedNode != null && !remote.nodeId().equals(expectedNode)) {
            throw new RpcProtocolException("RPC HELLO peer identity mismatch");
        }
    }

    private static Socket configuredSocket() throws IOException {
        Socket socket = new Socket(); socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        socket.setReceiveBufferSize(256 * 1024); socket.setSendBufferSize(256 * 1024); return socket;
    }

    private static void writeFrame(OutputStream output, Object writeLock, RpcFrame frame) throws IOException {
        byte[] encoded = RpcFrameCodecV1.encode(frame);
        synchronized (writeLock) { output.write(encoded); output.flush(); }
    }

    private static final class FrameInput {
        private final InputStream input; private final RpcFrameDecoder decoder = new RpcFrameDecoder(FRAME_BYTES);
        private final byte[] buffer = new byte[64 * 1024]; private final java.util.ArrayDeque<RpcFrame> ready = new java.util.ArrayDeque<>();
        private FrameInput(InputStream input) { this.input = input; }
        private RpcFrame next() throws IOException {
            while (ready.isEmpty()) {
                int count = input.read(buffer); if (count < 0) throw new IOException("unexpected RPC connection EOF");
                ready.addAll(decoder.feed(ByteBuffer.wrap(buffer, 0, count)));
            }
            return ready.removeFirst();
        }
    }

    private record RegisteredOperation(RpcOperationDescriptor descriptor, RpcHandler handler) {}

    private static final class DevelopmentServer implements RpcServer {
        private final RpcIdentity identity; private final ServerSocket listener;
        private final RpcEndpoint endpoint; private final Map<Integer, RegisteredOperation> operations = new ConcurrentHashMap<>();
        private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        private final SetOfConnections open = new SetOfConnections(); private final AtomicBoolean closed = new AtomicBoolean();

        private DevelopmentServer(RpcIdentity identity, String host, int port) {
            this.identity = Objects.requireNonNull(identity, "identity");
            if (host == null || host.isBlank() || port < 0 || port > 65_535) throw new IllegalArgumentException("invalid bind endpoint");
            try {
                listener = new ServerSocket(); listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(InetAddress.getByName(host), port));
                endpoint = RpcEndpoint.of(host, listener.getLocalPort()); connections.submit(this::acceptLoop);
            } catch (IOException failure) { throw new IllegalStateException("cannot bind development RPC server", failure); }
        }

        @Override public void register(RpcOperationDescriptor operation, RpcHandler handler) {
            if (closed.get()) throw new IllegalStateException("RPC server is closed");
            Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(handler, "handler");
            if (operations.putIfAbsent(operation.operationCode(), new RegisteredOperation(operation, handler)) != null) {
                throw new IllegalArgumentException("duplicate RPC operation code: " + operation.operationCode());
            }
        }
        @Override public RpcEndpoint endpoint() { return endpoint; }

        private void acceptLoop() {
            while (!closed.get()) try {
                Socket socket = listener.accept(); socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                socket.setReceiveBufferSize(256 * 1024); socket.setSendBufferSize(256 * 1024);
                open.add(socket); connections.submit(() -> serve(socket));
            } catch (IOException failure) { if (!closed.get()) close(); }
        }

        private void serve(Socket socket) {
            Object writeLock = new Object(); Map<Long, ServerStream> streams = new ConcurrentHashMap<>();
            AtomicLong inboundBytes = new AtomicLong();
            try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
                FrameInput frames = new FrameInput(input); RpcFrame incomingHello = frames.next();
                if (incomingHello.header().type() != RpcFrameType.HELLO) throw new RpcProtocolException("HELLO must be first frame");
                validatePeer(identity, RpcHelloV1.decode(incomingHello.payload()), RpcHelloV1.Role.DIALER, null);
                writeFrame(output, writeLock, helloFrame(hello(identity, RpcHelloV1.Role.ACCEPTOR)));
                while (!closed.get()) applyServerFrame(frames.next(), streams, inboundBytes, output, writeLock);
            } catch (IOException | RuntimeException ignored) {
                streams.values().forEach(stream -> stream.cancellation.cancel());
            } finally { open.remove(socket); }
        }

        private void applyServerFrame(RpcFrame frame, Map<Long, ServerStream> streams, AtomicLong inboundBytes,
                                      OutputStream output, Object writeLock) {
            RpcFrameHeaderV1 header = frame.header();
            if (header.type() == RpcFrameType.CANCEL) {
                ServerStream stream = streams.get(header.streamId());
                if (stream != null) {
                    stream.cancellation.cancel();
                    if (stream.assembler.isAssembling()) releaseServerStream(
                            streams, inboundBytes, header.streamId(), stream);
                }
                return;
            }
            if (header.type() != RpcFrameType.REQUEST) throw new RpcProtocolException("unexpected server-side frame: " + header.type());
            RegisteredOperation operation = operations.get(header.code());
            int limit = operation == null ? MESSAGE_BYTES : operation.descriptor.requestLimit();
            ServerStream stream = streams.get(header.streamId());
            if (stream == null) {
                if (!header.beginsMessage()) throw new RpcProtocolException("fragment without admitted stream");
                reserveInbound(inboundBytes, header.messageLength());
                ServerStream created = new ServerStream(header.invocationId(), new RpcMessageAssembler(limit),
                        new Cancellation(), header.timeoutMillis(), header.code(), header.messageLength());
                stream = streams.putIfAbsent(header.streamId(), created);
                if (stream != null) { inboundBytes.addAndGet(-header.messageLength()); throw new RpcProtocolException("duplicate stream BEGIN"); }
                stream = created;
            }
            if (!stream.invocation.equals(header.invocationId())) throw new RpcProtocolException("stream invocation changed");
            byte[] body = stream.assembler.accept(frame); if (body == null) return;
            if (operation == null) {
                releaseServerStream(streams, inboundBytes, header.streamId(), stream);
                respond(output, writeLock, header.streamId(), header.invocationId(), RpcStatus.INVALID_ARGUMENT,
                        "unknown operation".getBytes(java.nio.charset.StandardCharsets.UTF_8), MESSAGE_BYTES); return;
            }
            if (body.length > operation.descriptor.requestLimit()) throw new RpcProtocolException("request exceeds operation limit");
            long timeoutMillis = stream.timeoutMillis == 0
                    ? operation.descriptor.defaultTimeout().toMillis() : stream.timeoutMillis;
            Instant deadline = Instant.now().plusMillis(timeoutMillis);
            ServerStream admitted = stream;
            Runnable dispatch = () -> invoke(operation, body, header, admitted, streams, inboundBytes,
                    deadline, output, writeLock);
            // All current lanes are isolated from the connection reader; policy remains observable for later pools.
            handlers.submit(dispatch);
        }

        private void invoke(RegisteredOperation operation, byte[] body, RpcFrameHeaderV1 header,
                            ServerStream stream, Map<Long, ServerStream> streams, AtomicLong inboundBytes,
                            Instant deadline,
                            OutputStream output, Object writeLock) {
            AtomicBoolean completed = new AtomicBoolean();
            RpcResponder responder = new RpcResponder() {
                @Override public void success(byte[] response) { finish(RpcStatus.OK, response, ""); }
                @Override public void fail(RpcStatus status, String detail) { finish(status, new byte[0], detail); }
                private void finish(RpcStatus status, byte[] response, String detail) {
                    if (!completed.compareAndSet(false, true)) throw new IllegalStateException("RPC responder completed twice");
                    releaseServerStream(streams, inboundBytes, header.streamId(), stream);
                    if (status == null || status == RpcStatus.OK && !detail.isEmpty()) throw new IllegalArgumentException("invalid RPC response status");
                    byte[] payload = status == RpcStatus.OK ? response : detail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    int limit = status == RpcStatus.OK ? operation.descriptor.responseLimit() : 4096;
                    if (payload == null || payload.length > limit) {
                        status = RpcStatus.RESOURCE_EXHAUSTED; payload = "response exceeds operation limit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        limit = 4096;
                    }
                    respond(output, writeLock, header.streamId(), header.invocationId(), status, payload,
                            limit);
                }
            };
            try {
                if (Instant.now().isAfter(deadline)) responder.fail(RpcStatus.DEADLINE_EXCEEDED, "deadline elapsed before dispatch");
                else operation.handler.handle(new RpcServerRequest(operation.descriptor.operationCode(),
                        header.invocationId(), body, deadline, stream.cancellation), responder);
            } catch (RuntimeException | Error failure) {
                if (completed.compareAndSet(false, true)) { releaseServerStream(streams, inboundBytes, header.streamId(), stream); respond(output, writeLock, header.streamId(),
                        header.invocationId(), RpcStatus.INTERNAL, "handler failed".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        operation.descriptor.responseLimit()); }
            }
        }

        private static void reserveInbound(AtomicLong inboundBytes, int bytes) {
            while (true) {
                long current = inboundBytes.get(), updated = current + bytes;
                if (updated > MESSAGE_BYTES) throw new RpcProtocolException("connection inbound assembly budget exhausted");
                if (inboundBytes.compareAndSet(current, updated)) return;
            }
        }

        private static void releaseServerStream(Map<Long, ServerStream> streams, AtomicLong inboundBytes,
                                                long streamId, ServerStream stream) {
            if (streams.remove(streamId, stream)) inboundBytes.addAndGet(-stream.reservedBytes);
        }

        private static void respond(OutputStream output, Object writeLock, long stream, UUID invocation,
                                    RpcStatus status, byte[] payload, int responseLimit) {
            byte[] bounded = payload.length <= responseLimit ? payload : new byte[0];
            try { for (RpcFrame frame : RpcMessageFragmenter.fragment(RpcFrameType.RESPONSE, stream,
                    status.code(), invocation, 0, bounded, FRAME_BYTES)) writeFrame(output, writeLock, frame); }
            catch (IOException ignored) { /* Connection reader observes terminal socket failure. */ }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { listener.close(); } catch (IOException ignored) { }
            open.closeAll(); connections.shutdownNow(); handlers.shutdownNow();
        }
    }

    private record ServerStream(UUID invocation, RpcMessageAssembler assembler, Cancellation cancellation,
                                int timeoutMillis, int operationCode, int reservedBytes) {}

    private static final class DevelopmentClient implements RpcClient {
        private final RpcIdentity identity; private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
        private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("aether-rpc-deadlines").factory());
        private final AtomicBoolean closed = new AtomicBoolean();
        private DevelopmentClient(RpcIdentity identity) { this.identity = Objects.requireNonNull(identity, "identity"); }

        @Override public CompletableFuture<RpcResponse> call(RpcEndpoint peer, RpcOperationDescriptor operation,
                                                              byte[] body, RpcCallOptions options) {
            if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("RPC client is closed"));
            if (peer == null || operation == null || body == null || options == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("null RPC call argument"));
            }
            if (body.length > operation.requestLimit()) return CompletableFuture.failedFuture(
                    new IllegalArgumentException("request exceeds operation limit"));
            if (options.automaticRetry() && operation.retryClass() != RpcRetryClass.IDEMPOTENT) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "automatic retry requires an IDEMPOTENT operation; deduplicated retry is not configured"));
            }
            CompletableFuture<RpcResponse> first = callOnce(peer, operation, body, options);
            if (!options.automaticRetry()) return first;
            CompletableFuture<RpcResponse> result = new CompletableFuture<>();
            first.whenComplete((response, failure) -> {
                if (failure == null) { result.complete(response); return; }
                ClientConnection stale = connections.remove(peer.canonical()); if (stale != null) stale.close();
                callOnce(peer, operation, body, new RpcCallOptions(
                        options.timeout(), false, options.backpressureMode()))
                        .whenComplete((retried, retryFailure) -> {
                            if (retryFailure == null) result.complete(retried);
                            else result.completeExceptionally(retryFailure);
                        });
            });
            return result;
        }

        private CompletableFuture<RpcResponse> callOnce(RpcEndpoint peer, RpcOperationDescriptor operation,
                                                         byte[] body, RpcCallOptions options) {
            try {
                ClientConnection connection = connections.compute(peer.canonical(), (ignored, existing) ->
                        existing != null && !existing.closed.get() ? existing : connect(peer));
                return connection.submit(operation, body, options, deadlines);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        }

        private ClientConnection connect(RpcEndpoint endpoint) {
            Socket socket = null;
            try {
                socket = configuredSocket(); socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 3000);
                Object writeLock = new Object(); FrameInput input = new FrameInput(socket.getInputStream());
                writeFrame(socket.getOutputStream(), writeLock, helloFrame(hello(identity, RpcHelloV1.Role.DIALER)));
                RpcFrame peerFrame = input.next(); if (peerFrame.header().type() != RpcFrameType.HELLO) throw new RpcProtocolException("HELLO must be first frame");
                RpcHelloV1 peer = RpcHelloV1.decode(peerFrame.payload());
                validatePeer(identity, peer, RpcHelloV1.Role.ACCEPTOR, endpoint.expectedNodeId());
                return new ClientConnection(socket, input, writeLock, peer.nodeId());
            } catch (IOException | RuntimeException failure) {
                if (socket != null) try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw new IllegalStateException("RPC peer unavailable: " + endpoint.canonical(), failure);
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            connections.values().forEach(ClientConnection::close); connections.clear(); deadlines.shutdownNow();
        }
    }

    private static final class ClientConnection {
        private final Socket socket; private final FrameInput input; private final Object writeLock; private final UUID peerNodeId;
        private final OutputStream output; private final RpcStreamIdAllocator streams = new RpcStreamIdAllocator(RpcStreamIdAllocator.Role.DIALER);
        private final Map<Long, PendingCall> pending = new ConcurrentHashMap<>(); private final Semaphore outbound = new Semaphore(OUTBOUND_PERMITS, true);
        private final AtomicLong inboundBytes = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ClientConnection(Socket socket, FrameInput input, Object writeLock, UUID peerNodeId) throws IOException {
            this.socket = socket; this.input = input; this.writeLock = writeLock; this.peerNodeId = peerNodeId;
            output = socket.getOutputStream(); Thread.ofVirtual().name("aether-rpc-client-reader-" + peerNodeId).start(this::readLoop);
        }

        private CompletableFuture<RpcResponse> submit(RpcOperationDescriptor operation, byte[] body,
                                                       RpcCallOptions options, ScheduledExecutorService deadlines) {
            if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("RPC connection is closed"));
            int permits = permits(body.length); boolean acquired;
            try {
                acquired = options.backpressureMode() == RpcBackpressureMode.FAIL_FAST
                        ? outbound.tryAcquire(permits)
                        : outbound.tryAcquire(permits, options.timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return CompletableFuture.failedFuture(interrupted);
            }
            if (!acquired) return CompletableFuture.failedFuture(new IllegalStateException("RESOURCE_EXHAUSTED: outbound RPC queue"));
            long stream = streams.nextId(); UUID invocation = UUID.randomUUID();
            PendingCall call = new PendingCall(invocation,
                    new RpcMessageAssembler(Math.max(operation.responseLimit(), 4096)), permits);
            pending.put(stream, call);
            try {
                int timeout = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, options.timeout().toMillis())));
                for (RpcFrame frame : RpcMessageFragmenter.fragment(RpcFrameType.REQUEST, stream,
                        operation.operationCode(), invocation, timeout, body, FRAME_BYTES)) writeFrame(output, writeLock, frame);
            } catch (IOException | RuntimeException failure) {
                pending.remove(stream); outbound.release(permits); call.future.completeExceptionally(failure); close(); return call.future;
            }
            deadlines.schedule(() -> timeout(stream, call), options.timeout().toNanos(), TimeUnit.NANOSECONDS);
            call.future.whenComplete((ignored, failure) -> { if (call.future.isCancelled()) cancel(stream, call); });
            return call.future;
        }

        private void readLoop() {
            try { while (!closed.get()) apply(input.next()); }
            catch (IOException | RuntimeException failure) { failAll(failure); close(); }
        }

        private void apply(RpcFrame frame) {
            if (frame.header().type() != RpcFrameType.RESPONSE) throw new RpcProtocolException("unexpected client-side frame");
            PendingCall call = pending.get(frame.header().streamId());
            if (call == null) return; // A late response after timeout/cancel is intentionally ignored.
            if (!call.invocation.equals(frame.header().invocationId())) throw new RpcProtocolException("response invocation mismatch");
            if (frame.header().beginsMessage() && call.reservedBytes.compareAndSet(0, frame.header().messageLength())) {
                try { reserveClientInbound(frame.header().messageLength()); }
                catch (RuntimeException failure) { call.reservedBytes.set(0); throw failure; }
            }
            byte[] payload = call.assembler.accept(frame); if (payload == null) return;
            pending.remove(frame.header().streamId()); outbound.release(call.permits); releaseClientInbound(call);
            RpcStatus status = RpcStatus.fromCode(frame.header().code());
            String detail = status == RpcStatus.OK ? "" : new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            call.future.complete(new RpcResponse(status, call.invocation,
                    status == RpcStatus.OK ? payload : new byte[0], detail));
        }

        private void timeout(long stream, PendingCall call) {
            if (!pending.remove(stream, call)) return; outbound.release(call.permits); releaseClientInbound(call);
            call.future.complete(new RpcResponse(RpcStatus.DEADLINE_EXCEEDED, call.invocation,
                    new byte[0], "deadline exceeded")); sendCancel(stream, call.invocation);
        }
        private void cancel(long stream, PendingCall call) {
            if (!pending.remove(stream, call)) return; outbound.release(call.permits); releaseClientInbound(call); sendCancel(stream, call.invocation);
        }
        private void sendCancel(long stream, UUID invocation) {
            try { writeFrame(output, writeLock, new RpcFrame(new RpcFrameHeaderV1(RpcFrameType.CANCEL,
                    0, stream, 0, 0, 0, 0, 0, 0, invocation), new byte[0])); }
            catch (IOException ignored) { close(); }
        }
        private void failAll(Throwable failure) {
            List<PendingCall> calls = new ArrayList<>(pending.values()); pending.clear();
            calls.forEach(call -> { outbound.release(call.permits); releaseClientInbound(call); call.future.completeExceptionally(failure); });
        }
        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) { }
            failAll(new IOException("RPC connection closed for peer " + peerNodeId));
        }
        private static int permits(int bytes) { return Math.max(1, Math.toIntExact((bytes + (long) PERMIT_BYTES - 1) / PERMIT_BYTES)); }
        private void reserveClientInbound(int bytes) {
            while (true) {
                long current = inboundBytes.get(), updated = current + bytes;
                if (updated > MESSAGE_BYTES) throw new RpcProtocolException("connection inbound response budget exhausted");
                if (inboundBytes.compareAndSet(current, updated)) return;
            }
        }
        private void releaseClientInbound(PendingCall call) {
            int reserved = call.reservedBytes.getAndSet(0); if (reserved != 0) inboundBytes.addAndGet(-reserved);
        }
    }

    private static final class PendingCall {
        private final UUID invocation;
        private final RpcMessageAssembler assembler; private final int permits;
        private final AtomicInteger reservedBytes = new AtomicInteger();
        private final CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        private PendingCall(UUID invocation, RpcMessageAssembler assembler, int permits) {
            this.invocation = invocation; this.assembler = assembler; this.permits = permits;
        }
    }

    private static final class Cancellation implements RpcCancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final List<Runnable> callbacks = new ArrayList<>();
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public synchronized void onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback"); if (cancelled.get()) callback.run(); else callbacks.add(callback);
        }
        private void cancel() {
            List<Runnable> notify;
            synchronized (this) { if (!cancelled.compareAndSet(false, true)) return; notify = List.copyOf(callbacks); callbacks.clear(); }
            notify.forEach(Runnable::run);
        }
    }

    private static final class SetOfConnections {
        private final java.util.Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private void add(Socket socket) { sockets.add(socket); }
        private void remove(Socket socket) { sockets.remove(socket); }
        private void closeAll() { sockets.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } }); sockets.clear(); }
    }
}
