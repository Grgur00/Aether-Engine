package io.aetherdb.training.cache;

import java.io.DataInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

final class TrainingCacheProtocol {
    private static final int VERSION = 1;
    private static final int GET = 1;
    private static final int PUT = 2;
    private static final int GET_REF = 3;
    private static final int GET_MANY_REF = 4;
    private static final int GET_MANY = 5;
    private static final int PUT_MANY = 6;
    private static final int INFO = 7;
    private static final int CONTAINS_MANY = 8;
    private static final int DRAIN_TRACES = 9;
    private static final int HIT = 1;
    private static final int MISS = 0;
    private static final int ERROR = 2;
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private TrainingCacheProtocol() {}

    static void serve(InputStream rawInput, OutputStream rawOutput, TrainingCache cache, Semaphore permits,
            TrainingCacheProtocolMetrics metrics) {
        metrics.connection();
        try (DataInputStream input = new DataInputStream(rawInput);
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput))) {
            while (true) {
                // Start after the first header byte arrives, excluding idle connection wait.
                int first = input.read();
                if (first < 0) return;
                long arrived = System.nanoTime();
                int frameBytes = (first << 24) | (input.readUnsignedByte() << 16)
                        | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
                if (frameBytes < 2 || frameBytes > MAX_FRAME_BYTES) throw new IOException("invalid frame size");
                byte[] frame = new byte[frameBytes];
                input.readFully(frame);
                long readFinished = System.nanoTime();
                boolean acquired = false;
                try {
                    ByteBuffer request = ByteBuffer.wrap(frame);
                    int version = Byte.toUnsignedInt(request.get());
                    if (version != VERSION && version != 2) throw new IOException("unsupported protocol version");
                    int operation = Byte.toUnsignedInt(request.get());
                    if (version == 2) {
                        byte[] traceId = new byte[16];
                        request.get(traceId);
                        TrainingCacheRequestTrace.begin(traceId);
                        TrainingCacheRequestTrace.requestStarted(arrived, readFinished - arrived);
                    }
                    Decoded decoded = decode(operation, request);
                    TrainingCacheRequestTrace.add("requestDecode", System.nanoTime() - readFinished);
                    long dispatchStarted = TrainingCacheRequestTrace.start();
                    Response response;
                    try {
                        acquired = permits.tryAcquire();
                        if (!acquired) response = new Response(ERROR, new byte[0]);
                        else { metrics.request(operation); response = dispatch(decoded, cache); }
                    } finally { TrainingCacheRequestTrace.end("dispatch", dispatchStarted); }
                    writeResponse(output, response.status(), response.value());
                    long completedAt = System.nanoTime();
                    if (operation == GET_MANY && acquired)
                        cache.recordCompletedTrace(TrainingCacheRequestTrace.completed(completedAt));
                } finally {
                    if (acquired) permits.release();
                    TrainingCacheRequestTrace.clear();
                }
            }
        } catch (Exception ignored) { }
    }

    private record Decoded(int operation, java.util.List<CacheKey> keys, java.util.List<CacheEntry> entries) {}
    private record Response(int status, byte[] value) {}

    private static Decoded decode(int operation, ByteBuffer input) throws IOException {
        if (operation < GET || operation > DRAIN_TRACES) throw new IOException("unsupported operation");
        var keys = new java.util.ArrayList<CacheKey>();
        var entries = new java.util.ArrayList<CacheEntry>();
        if (operation != INFO && operation != DRAIN_TRACES) {
            int count = operation == GET_MANY || operation == GET_MANY_REF
                    || operation == PUT_MANY || operation == CONTAINS_MANY ? input.getInt() : 1;
            if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid batch size");
            for (int index = 0; index < count; index++) {
                CacheKey key = readKey(input);
                keys.add(key);
                if (operation == PUT || operation == PUT_MANY) entries.add(new CacheEntry(key, readValue(input)));
            }
        }
        return new Decoded(operation, keys, entries);
    }

    private static Response dispatch(Decoded request, TrainingCache cache) throws IOException {
        return switch (request.operation()) {
            case INFO -> new Response(HIT, TrainingCacheRequestTrace.measure("responseEncode", () ->
                    DiagnosticJson.encode(java.util.Map.of("engine", "java-training-cache",
                            "durability", cache.durability().name(), "pid", ProcessHandle.current().pid(),
                            "cacheEntries", cache.cacheEntries(), "integrityPolicy", cache.integrityPolicy(),
                            "backgroundCompaction", cache.compactionDiagnostics()))
                            .getBytes(StandardCharsets.UTF_8)));
            case DRAIN_TRACES -> new Response(HIT,
                    DiagnosticJson.encode(cache.drainCompletedTraces()).getBytes(StandardCharsets.UTF_8));
            case PUT, PUT_MANY -> {
                TrainingCacheRequestTrace.measure("cacheOperation", () -> { cache.putMany(request.entries()); return null; });
                yield new Response(HIT, new byte[0]);
            }
            case GET -> {
                byte[] value = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.get(request.keys().get(0)));
                yield new Response(value == null ? MISS : HIT, value == null ? new byte[0] : value);
            }
            case GET_MANY -> new Response(HIT,
                    TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getManyValuesWire(request.keys())));
            case GET_REF -> {
                SegmentReference ref = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getRef(request.keys().get(0)));
                yield new Response(ref == null ? MISS : HIT, ref == null ? new byte[0]
                        : TrainingCacheRequestTrace.measure("responseEncode", () -> encodeReference(ref)));
            }
            case GET_MANY_REF -> {
                var refs = new java.util.ArrayList<SegmentReference>();
                for (CacheKey key : request.keys()) refs.add(TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getRef(key)));
                yield new Response(HIT, TrainingCacheRequestTrace.measureIo("responseEncode", () -> {
                    var encoded = new java.io.ByteArrayOutputStream();
                    var out = new DataOutputStream(encoded);
                    out.writeInt(refs.size());
                    for (SegmentReference ref : refs) {
                        out.writeBoolean(ref != null);
                        if (ref != null) out.write(encodeReference(ref));
                    }
                    return encoded.toByteArray();
                }));
            }
            case CONTAINS_MANY -> {
                var present = new java.util.ArrayList<Boolean>();
                for (CacheKey key : request.keys()) present.add(
                        TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.containsKey(key)));
                yield new Response(HIT, TrainingCacheRequestTrace.measure("responseEncode", () -> {
                    ByteBuffer result = ByteBuffer.allocate(4 + present.size()).putInt(present.size());
                    for (boolean found : present) result.put((byte) (found ? 1 : 0));
                    return result.array();
                }));
            }
            default -> throw new IOException("unsupported operation");
        };
    }

    private static byte[] encodeReference(SegmentReference reference) {
        byte[] segment = reference.segmentId().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer output = ByteBuffer.allocate(4 + segment.length + 8 + 8 + 4 + 32);
        output.putInt(segment.length).put(segment).putLong(reference.generation()).putLong(reference.offset())
                .putInt(reference.length()).put(reference.checksum());
        return output.array();
    }

    private static CacheKey readKey(ByteBuffer input) {
        String namespace = readString(input);
        String sample = readString(input);
        byte[] fingerprint = new byte[TransformationFingerprint.BYTES]; input.get(fingerprint);
        return new CacheKey(namespace, sample, TransformationFingerprint.fromBytes(fingerprint));
    }

    private static byte[] readValue(ByteBuffer input) {
        int length = input.getInt();
        if (length < 0 || length > MAX_FRAME_BYTES || length > input.remaining()) throw new IllegalArgumentException("invalid value length");
        byte[] value = new byte[length]; input.get(value); return value;
    }

    private static String readString(ByteBuffer input) {
        int length = input.getInt();
        if (length < 0 || length > input.remaining()) throw new IllegalArgumentException("invalid string length");
        byte[] value = new byte[length]; input.get(value); return new String(value, StandardCharsets.UTF_8);
    }

    private static void writeResponse(DataOutputStream output, int status, byte[] value) throws IOException {
        long encodeStarted = TrainingCacheRequestTrace.start();
        value = TrainingCacheRequestTrace.envelope(value);
        TrainingCacheRequestTrace.end("responseEncode", encodeStarted);
        long writeStarted = TrainingCacheRequestTrace.start();
        output.writeInt(1 + 4 + value.length); output.writeByte(status); output.writeInt(value.length); output.write(value); output.flush();
        TrainingCacheRequestTrace.end("responseWrite", writeStarted);
    }
}
