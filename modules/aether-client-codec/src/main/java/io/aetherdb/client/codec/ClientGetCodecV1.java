package io.aetherdb.client.codec;

import io.aetherdb.client.api.ClientGetRequest;
import io.aetherdb.client.api.ClientGetResponse;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact v1 codecs for client point-read request and response bodies. */
public final class ClientGetCodecV1 {
    private static final int REQUEST_MAGIC = 0x41454751;
    private static final int RESPONSE_MAGIC = 0x41454752;
    private static final int HEADER_BYTES = 64;

    private ClientGetCodecV1() {}

    /** Encodes one point-read request. */
    public static byte[] encodeRequest(ClientGetRequest request) {
        byte[] out = new byte[HEADER_BYTES + request.key().length];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(REQUEST_MAGIC)
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putLong(request.configurationVersion())
                .putInt(request.key().length)
                .putLong(0)
                .putLong(0)
                .putLong(0)
                .putLong(0);
        b.position(HEADER_BYTES);
        b.put(request.key());
        putChecksum(out);
        return out;
    }

    /** Decodes one point-read request. */
    public static ClientGetRequest decodeRequest(byte[] in) {
        if (!validEnvelope(in)) throw invalidRequest();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != REQUEST_MAGIC || b.getShort() != 1 || b.getShort() != HEADER_BYTES) {
            throw invalidRequest();
        }
        long configurationVersion = b.getLong();
        int keyLength = b.getInt();
        if (b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0) {
            throw invalidRequest();
        }
        if (keyLength <= 0 || keyLength > 65_536 || in.length != HEADER_BYTES + keyLength) {
            throw invalidRequest();
        }
        return new ClientGetRequest(configurationVersion, Arrays.copyOfRange(in, HEADER_BYTES, in.length));
    }

    /** Encodes one point-read response. */
    public static byte[] encodeResponse(ClientGetResponse response) {
        byte[] detail = response.detail().getBytes(StandardCharsets.UTF_8);
        if (detail.length > 4096) throw new IllegalArgumentException("response too large");
        byte[] out = new byte[HEADER_BYTES + response.value().length + detail.length];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(RESPONSE_MAGIC)
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putInt(response.status().ordinal())
                .putInt(response.value().length)
                .putInt(detail.length)
                .putLong(0)
                .putLong(0)
                .putLong(0)
                .putLong(0);
        b.position(HEADER_BYTES);
        b.put(response.value()).put(detail);
        putChecksum(out);
        return out;
    }

    /** Decodes one point-read response. */
    public static ClientGetResponse decodeResponse(byte[] in) {
        if (!validEnvelope(in)) throw invalidResponse();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != RESPONSE_MAGIC || b.getShort() != 1 || b.getShort() != HEADER_BYTES) {
            throw invalidResponse();
        }
        int statusOrdinal = b.getInt();
        int valueLength = b.getInt();
        int detailLength = b.getInt();
        if (statusOrdinal < 0 || statusOrdinal >= ClientStatus.values().length) throw invalidResponse();
        if (b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0) {
            throw invalidResponse();
        }
        if (valueLength < 0 || valueLength > 16 * 1024 * 1024 || detailLength < 0 || detailLength > 4096) {
            throw invalidResponse();
        }
        if (in.length != HEADER_BYTES + valueLength + detailLength) throw invalidResponse();
        byte[] value = Arrays.copyOfRange(in, HEADER_BYTES, HEADER_BYTES + valueLength);
        byte[] detail = Arrays.copyOfRange(in, HEADER_BYTES + valueLength, in.length);
        return new ClientGetResponse(
                ClientStatus.values()[statusOrdinal], value, new String(detail, StandardCharsets.UTF_8));
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
        return new IllegalArgumentException("invalid CLIENT_GET request body");
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("invalid CLIENT_GET response body");
    }
}
