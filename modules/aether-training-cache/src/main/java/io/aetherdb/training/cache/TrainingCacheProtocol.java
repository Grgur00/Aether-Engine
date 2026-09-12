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
    private static final int HIT = 1;
    private static final int MISS = 0;
    private static final int ERROR = 2;
    private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private TrainingCacheProtocol() {}

    static void serve(InputStream rawInput, OutputStream rawOutput, TrainingCache cache, Semaphore permits,
            TrainingCacheProtocolMetrics metrics) {
        metrics.connection();
        // Coalesce the small framing writes without copying an entire large response.
        try (DataInputStream input = new DataInputStream(rawInput);
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(rawOutput))) {
            while (true) {
                int frameBytes;
                try { frameBytes = input.readInt(); } catch (EOFException end) { return; }
                if (frameBytes < 1 || frameBytes > MAX_FRAME_BYTES) throw new IOException("invalid frame size");
                byte[] frame = new byte[frameBytes];
                input.readFully(frame);
                ByteBuffer request = ByteBuffer.wrap(frame);
                int version = Byte.toUnsignedInt(request.get());
                if (version != VERSION && version != 2) throw new IOException("unsupported protocol version");
                int operation = Byte.toUnsignedInt(request.get());
                if (version == 2) {
                    byte[] traceId = new byte[16];
                    request.get(traceId);
                    TrainingCacheRequestTrace.begin(traceId);
                }
                boolean acquired = false;
                try {
                    acquired = permits.tryAcquire();
                    if (!acquired) { writeResponse(output, ERROR, new byte[0]); continue; }
                    metrics.request(operation);
                    if (operation == CONTAINS_MANY) {
                        int count = request.getInt();
                        if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid presence batch size");
                        ByteBuffer result = ByteBuffer.allocate(4 + count).putInt(count);
                        for (int index = 0; index < count; index++)
                            result.put((byte) (TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.containsKey(readKey(request))) ? 1 : 0));
                        writeResponse(output, HIT, result.array());
                    } else if (operation == INFO) {
                        String info = "{\"engine\":\"java-training-cache\",\"durability\":\""
                                + cache.durability().name() + "\",\"pid\":" + ProcessHandle.current().pid() + "}";
                        writeResponse(output, HIT, info.getBytes(StandardCharsets.UTF_8));
                    } else if (operation == PUT_MANY) {
                        int count = request.getInt();
                        if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid put batch size");
                        java.util.ArrayList<CacheEntry> values = new java.util.ArrayList<>(count);
                        for (int index = 0; index < count; index++)
                            values.add(new CacheEntry(readKey(request), readValue(request)));
                        TrainingCacheRequestTrace.measure("cacheOperation", () -> { cache.putMany(values); return null; });
                        writeResponse(output, HIT, new byte[0]);
                    } else if (operation == GET_MANY_REF) {
                        int count = request.getInt();
                        if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid reference batch size");
                        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream references = new java.io.DataOutputStream(encoded);
                        references.writeInt(count);
                        for (int index = 0; index < count; index++) {
                            CacheKey key = readKey(request);
                            SegmentReference reference = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getRef(key));
                            if (reference == null) references.writeBoolean(false);
                            else { references.writeBoolean(true); references.write(encodeReference(reference)); }
                        }
                        writeResponse(output, HIT, encoded.toByteArray());
                    } else if (operation == GET_MANY) {
                        int count = request.getInt();
                        if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid value batch size");
                        java.util.ArrayList<CacheKey> keys = new java.util.ArrayList<>(count);
                        for (int index = 0; index < count; index++) keys.add(readKey(request));
                        BatchValueResult batch = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getManyValues(keys));
                        ByteBuffer payload = batch.payload().duplicate();
                        ByteBuffer result = ByteBuffer.allocate(Math.addExact(4 + 9 * count, payload.remaining()));
                        result.putInt(count);
                        for (BatchValueResult.ValueStatus status : batch.statuses())
                            result.put((byte) (status == BatchValueResult.ValueStatus.MISS ? 0 : 1));
                        for (int offset : batch.offsets()) result.putInt(offset);
                        for (int length : batch.lengths()) result.putInt(length);
                        result.put(payload);
                        writeResponse(output, HIT, result.array());
                    } else {
                    CacheKey key = readKey(request);
                    if (operation == GET_REF) {
                        SegmentReference reference = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.getRef(key));
                        writeResponse(output, reference == null ? MISS : HIT,
                                reference == null ? new byte[0] : encodeReference(reference));
                    } else if (operation == GET) {
                        byte[] value = TrainingCacheRequestTrace.measure("cacheOperation", () -> cache.get(key));
                        writeResponse(output, value == null ? MISS : HIT, value == null ? new byte[0] : value);
                    } else if (operation == PUT) {
                        byte[] value = readValue(request);
                        TrainingCacheRequestTrace.measure("cacheOperation", () -> { cache.put(key, value); return null; });
                        writeResponse(output, HIT, new byte[0]);
                    } else throw new IOException("unsupported operation");
                    }
                } finally {
                    if (acquired) permits.release();
                    TrainingCacheRequestTrace.clear();
                }
            }
        } catch (Exception ignored) { }
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
        value = TrainingCacheRequestTrace.envelope(value);
        output.writeInt(1 + 4 + value.length); output.writeByte(status); output.writeInt(value.length); output.write(value); output.flush();
    }
}
