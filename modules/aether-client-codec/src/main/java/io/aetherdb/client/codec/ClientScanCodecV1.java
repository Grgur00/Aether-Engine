package io.aetherdb.client.codec;

import io.aetherdb.client.api.ClientScanEntry;
import io.aetherdb.client.api.ClientScanRequest;
import io.aetherdb.client.api.ClientScanResponse;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact v1 codecs for paged client scan request and response bodies. */
public final class ClientScanCodecV1 {
    private static final int REQUEST_MAGIC = 0x41455351;
    private static final int RESPONSE_MAGIC = 0x41455352;
    private static final int HEADER_BYTES = 64;

    private ClientScanCodecV1() {}

    /** Encodes a scan-open or scan-next request. */
    public static byte[] encodeRequest(ClientScanRequest request) {
        int regionBytes =
                request.startInclusive().length
                        + request.endExclusive().length
                        + request.pageToken().length;
        byte[] out = new byte[HEADER_BYTES + regionBytes];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(REQUEST_MAGIC)
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putLong(request.configurationVersion())
                .putInt(request.maxEntries())
                .putInt(request.startInclusive().length)
                .putInt(request.endExclusive().length)
                .putInt(request.pageToken().length)
                .putLong(0)
                .putLong(0)
                .putLong(0);
        b.position(HEADER_BYTES);
        b.put(request.startInclusive()).put(request.endExclusive()).put(request.pageToken());
        putChecksum(out);
        return out;
    }

    /** Decodes a scan-open or scan-next request. */
    public static ClientScanRequest decodeRequest(byte[] in) {
        if (!validEnvelope(in)) throw invalidRequest();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != REQUEST_MAGIC || b.getShort() != 1 || b.getShort() != HEADER_BYTES) {
            throw invalidRequest();
        }
        long configurationVersion = b.getLong();
        int maxEntries = b.getInt();
        int startLength = b.getInt();
        int endLength = b.getInt();
        int tokenLength = b.getInt();
        if (b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0) throw invalidRequest();
        if (startLength <= 0
                || endLength <= 0
                || startLength > 65_536
                || endLength > 65_536
                || tokenLength < 0
                || tokenLength > 4096
                || in.length != HEADER_BYTES + startLength + endLength + tokenLength) {
            throw invalidRequest();
        }
        int position = HEADER_BYTES;
        byte[] start = Arrays.copyOfRange(in, position, position + startLength);
        position += startLength;
        byte[] end = Arrays.copyOfRange(in, position, position + endLength);
        position += endLength;
        byte[] token = Arrays.copyOfRange(in, position, position + tokenLength);
        return new ClientScanRequest(configurationVersion, start, end, maxEntries, token);
    }

    /** Encodes one scan response page. */
    public static byte[] encodeResponse(ClientScanResponse response) {
        byte[] detail = response.detail().getBytes(StandardCharsets.UTF_8);
        int entryBytes = 0;
        for (ClientScanEntry entry : response.entries()) {
            entryBytes = Math.addExact(entryBytes, 8 + entry.key().length + entry.value().length);
        }
        int regionBytes = entryBytes + response.nextPageToken().length + detail.length;
        byte[] out = new byte[HEADER_BYTES + regionBytes];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(RESPONSE_MAGIC)
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putInt(response.status().ordinal())
                .putInt(response.entries().size())
                .putInt(response.nextPageToken().length)
                .putInt(detail.length)
                .putLong(0)
                .putLong(0)
                .putLong(0)
                .putInt(0);
        b.position(HEADER_BYTES);
        for (ClientScanEntry entry : response.entries()) {
            b.putInt(entry.key().length).putInt(entry.value().length).put(entry.key()).put(entry.value());
        }
        b.put(response.nextPageToken()).put(detail);
        putChecksum(out);
        return out;
    }

    /** Decodes one scan response page. */
    public static ClientScanResponse decodeResponse(byte[] in) {
        if (!validEnvelope(in)) throw invalidResponse();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != RESPONSE_MAGIC || b.getShort() != 1 || b.getShort() != HEADER_BYTES) {
            throw invalidResponse();
        }
        int statusOrdinal = b.getInt();
        int entryCount = b.getInt();
        int tokenLength = b.getInt();
        int detailLength = b.getInt();
        if (b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0 || b.getInt() != 0) {
            throw invalidResponse();
        }
        if (statusOrdinal < 0
                || statusOrdinal >= ClientStatus.values().length
                || entryCount < 0
                || entryCount > 10_000
                || tokenLength < 0
                || tokenLength > 4096
                || detailLength < 0
                || detailLength > 4096) {
            throw invalidResponse();
        }
        b.position(HEADER_BYTES);
        List<ClientScanEntry> entries = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            if (b.remaining() < 8) throw invalidResponse();
            int keyLength = b.getInt();
            int valueLength = b.getInt();
            if (keyLength <= 0 || keyLength > 65_536 || valueLength < 0 || valueLength > 16 * 1024 * 1024) {
                throw invalidResponse();
            }
            if (b.remaining() < keyLength + valueLength) throw invalidResponse();
            byte[] key = new byte[keyLength];
            byte[] value = new byte[valueLength];
            b.get(key).get(value);
            entries.add(new ClientScanEntry(key, value));
        }
        if (b.remaining() != tokenLength + detailLength) throw invalidResponse();
        byte[] token = new byte[tokenLength];
        byte[] detail = new byte[detailLength];
        b.get(token).get(detail);
        return new ClientScanResponse(
                ClientStatus.values()[statusOrdinal],
                entries,
                token,
                new String(detail, StandardCharsets.UTF_8));
    }

    private static boolean validEnvelope(byte[] in) {
        return in != null
                && in.length >= HEADER_BYTES
                && ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN).getInt(60)
                        == MaskedCrc32c.masked(in, 0, 60);
    }

    private static void putChecksum(byte[] out) {
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(60, MaskedCrc32c.masked(out, 0, 60));
    }

    private static IllegalArgumentException invalidRequest() {
        return new IllegalArgumentException("invalid CLIENT_SCAN request body");
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("invalid CLIENT_SCAN response body");
    }
}
