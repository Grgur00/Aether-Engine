package io.aetherdb.training.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrainingCacheTraceTest {
    @TempDir Path temp;

    private byte[] request(int version, int operation, String sample, byte[] value) throws IOException {
        var bodyBytes = new ByteArrayOutputStream();
        var body = new DataOutputStream(bodyBytes);
        body.writeByte(version);
        body.writeByte(operation);
        if (version == 2) body.write(new byte[16]);
        if (operation == 5 || operation == 6) body.writeInt(1);
        for (String text : new String[] {"tracing", sample}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            body.writeInt(bytes.length); body.write(bytes);
        }
        body.write(TransformationFingerprint.ofCanonicalDescriptor("v1").bytes());
        if (operation == 2 || operation == 6) { body.writeInt(value.length); body.write(value); }
        var frame = new ByteArrayOutputStream();
        var output = new DataOutputStream(frame);
        output.writeInt(bodyBytes.size()); output.write(bodyBytes.toByteArray());
        return frame.toByteArray();
    }

    private byte[] readResponse(DataInputStream input, int status, boolean traced, String stage) throws IOException {
        int length = input.readInt();
        assertEquals(status, input.readUnsignedByte());
        int size = input.readInt();
        assertEquals(size + 5, length);
        byte[] value = input.readNBytes(size);
        assertEquals(size, value.length);
        if (!traced) return value;
        var envelope = new DataInputStream(new ByteArrayInputStream(value));
        String json = new String(envelope.readNBytes(envelope.readInt()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"traceId\":\"00000000000000000000000000000000\""));
        assertTrue(json.contains("\"serverDurationNs\":"));
        if (stage != null) assertTrue(json.contains("\"" + stage + "\":"), json);
        return envelope.readAllBytes();
    }

    @Test void correlatedBatchPutGetMissAndLegacyShareConnection() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        var frames = new ByteArrayOutputStream();
        frames.write(request(2, 6, "present", payload));
        frames.write(request(2, 5, "present", null));
        frames.write(request(2, 1, "absent", null));
        frames.write(request(1, 1, "present", null));
        var output = new ByteArrayOutputStream();
        var permits = new Semaphore(1);
        try (var cache = TrainingCache.open(temp.resolve("mixed"))) {
            TrainingCacheProtocol.serve(new ByteArrayInputStream(frames.toByteArray()), output,
                    cache, permits, new TrainingCacheProtocolMetrics());
        }
        var input = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(0, readResponse(input, 1, true, "databaseWriteAndSync").length);
        var batch = new DataInputStream(new ByteArrayInputStream(readResponse(input, 1, true, "indexLookup")));
        assertEquals(1, batch.readInt());
        assertEquals(1, batch.readUnsignedByte());
        assertEquals(0, batch.readInt());
        assertEquals(payload.length, batch.readInt());
        assertArrayEquals(payload, batch.readAllBytes());
        assertEquals(0, readResponse(input, 0, true, "indexLookup").length);
        assertArrayEquals(payload, readResponse(input, 1, false, null));
        assertEquals(0, input.available());
        assertEquals(1, permits.availablePermits());
        assertArrayEquals(payload, TrainingCacheRequestTrace.envelope(payload));
    }

    @Test void overloadedTraceResponseAndMalformedRequestClearContext() throws Exception {
        try (var cache = TrainingCache.open(temp.resolve("errors"))) {
            var output = new ByteArrayOutputStream();
            TrainingCacheProtocol.serve(new ByteArrayInputStream(request(2, 1, "absent", null)),
                    output, cache, new Semaphore(0), new TrainingCacheProtocolMetrics());
            readResponse(new DataInputStream(new ByteArrayInputStream(output.toByteArray())), 2, true, null);
            byte[] malformed = request(2, 99, "absent", null);
            TrainingCacheProtocol.serve(new ByteArrayInputStream(malformed), new ByteArrayOutputStream(),
                    cache, new Semaphore(1), new TrainingCacheProtocolMetrics());
            assertArrayEquals(new byte[] {1}, TrainingCacheRequestTrace.envelope(new byte[] {1}));
        }
    }

    @Test void mixedBatchesPreserveSegmentsDuplicatesMissesAndWireOffsets() throws Exception {
        var transform = TransformationFingerprint.ofCanonicalDescriptor("v1");
        byte[] inline = {7, 8, 9};
        byte[] segment = new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1];
        new java.util.Random(91).nextBytes(segment);
        try (var cache = TrainingCache.open(temp.resolve("batch-wire"))) {
            cache.put(new CacheKey("tracing", "inline", transform), inline);
            cache.put(new CacheKey("tracing", "segment", transform), segment);
            for (int version : new int[] {1, 2}) {
                var frames = new ByteArrayOutputStream();
                var bodyBytes = new ByteArrayOutputStream();
                var body = new DataOutputStream(bodyBytes);
                body.writeByte(version); body.writeByte(5);
                if (version == 2) body.write(new byte[16]);
                body.writeInt(4);
                for (String sample : new String[] {"inline", "segment", "missing", "inline"}) {
                    for (String text : new String[] {"tracing", sample}) {
                        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                        body.writeInt(bytes.length); body.write(bytes);
                    }
                    body.write(transform.bytes());
                }
                var framing = new DataOutputStream(frames);
                framing.writeInt(bodyBytes.size()); framing.write(bodyBytes.toByteArray());
                frames.write(request(1, 1, "inline", null));
                var output = new ByteArrayOutputStream();
                // Exercise partial input reads as well as multiple requests on one connection.
                var fragmented = new FilterInputStream(new ByteArrayInputStream(frames.toByteArray())) {
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        return super.read(bytes, offset, Math.min(length, 3));
                    }
                };
                TrainingCacheProtocol.serve(fragmented, output, cache, new Semaphore(1), new TrainingCacheProtocolMetrics());
                var responses = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
                var batch = new DataInputStream(new ByteArrayInputStream(readResponse(responses, 1, version == 2, "indexLookup")));
                assertEquals(4, batch.readInt());
                assertArrayEquals(new byte[] {1, 1, 0, 1}, batch.readNBytes(4));
                for (int offset : new int[] {0, inline.length, inline.length + segment.length, inline.length + segment.length})
                    assertEquals(offset, batch.readInt());
                for (int length : new int[] {inline.length, segment.length, 0, inline.length})
                    assertEquals(length, batch.readInt());
                assertArrayEquals(inline, batch.readNBytes(inline.length));
                assertArrayEquals(segment, batch.readNBytes(segment.length));
                assertArrayEquals(inline, batch.readAllBytes());
                assertArrayEquals(inline, readResponse(responses, 1, false, null));
                assertEquals(0, responses.available());
            }
        }
    }
}
