package io.aetherdb.client.codec;

import io.aetherdb.client.api.ClientEndpointHint;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientWriteResponse;
import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exact v1 codec for client write response bodies. */
public final class ClientWriteResponseCodecV1 {
    /** Fixed response-header length in bytes. */
    public static final int HEADER_BYTES = 128;
    private static final int MAGIC = 0x41454352;
    private static final int FLAG_LEADER_HINT = 1;

    private ClientWriteResponseCodecV1() {}

    /** Encodes a stable write response body. */
    public static byte[] encode(ClientWriteResponse response) {
        byte[] host =
                response.leaderHint() == null
                        ? new byte[0]
                        : response.leaderHint().host().getBytes(StandardCharsets.UTF_8);
        byte[] detail = response.detail().getBytes(StandardCharsets.UTF_8);
        if (host.length > 255 || detail.length > 4096) throw new IllegalArgumentException("response too large");

        int flags = response.leaderHint() == null ? 0 : FLAG_LEADER_HINT;
        int regionBytes = host.length + detail.length;
        byte[] out = new byte[HEADER_BYTES + regionBytes];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC)
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putInt(flags)
                .putInt(response.status().ordinal())
                .putLong(response.commandId().getMostSignificantBits())
                .putLong(response.commandId().getLeastSignificantBits())
                .putInt(response.operationCount())
                .putLong(response.firstSequence())
                .putLong(response.lastSequence());
        if (response.leaderHint() == null) {
            b.putInt(0).putLong(0).putLong(0);
        } else {
            UUID nodeId = response.leaderHint().expectedNodeId();
            b.putInt(response.leaderHint().port())
                    .putLong(nodeId == null ? 0 : nodeId.getMostSignificantBits())
                    .putLong(nodeId == null ? 0 : nodeId.getLeastSignificantBits());
        }
        b.putInt(host.length).putInt(detail.length).putLong(0).putLong(0).putLong(0).putLong(0);
        b.position(HEADER_BYTES);
        b.put(host).put(detail);
        ByteBuffer.wrap(out)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(124, MaskedCrc32c.masked(out, 0, 124));
        return out;
    }

    /** Decodes and validates a stable write response body. */
    public static ClientWriteResponse decode(byte[] in) {
        if (in == null
                || in.length < HEADER_BYTES
                || ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN).getInt(124)
                        != MaskedCrc32c.masked(in, 0, 124)) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.getShort() != 1 || b.getShort() != HEADER_BYTES) throw invalid();
        int flags = b.getInt();
        if ((flags & ~FLAG_LEADER_HINT) != 0) throw invalid();
        int statusOrdinal = b.getInt();
        if (statusOrdinal < 0 || statusOrdinal >= ClientStatus.values().length) throw invalid();
        UUID commandId = new UUID(b.getLong(), b.getLong());
        int operationCount = b.getInt();
        long firstSequence = b.getLong();
        long lastSequence = b.getLong();
        int port = b.getInt();
        long nodeMost = b.getLong();
        long nodeLeast = b.getLong();
        int hostLength = b.getInt();
        int detailLength = b.getInt();
        if (b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0 || b.getLong() != 0) throw invalid();
        if (hostLength < 0 || hostLength > 255 || detailLength < 0 || detailLength > 4096) throw invalid();
        if (in.length != HEADER_BYTES + hostLength + detailLength) throw invalid();

        byte[] hostBytes = new byte[hostLength];
        byte[] detailBytes = new byte[detailLength];
        b.position(HEADER_BYTES);
        b.get(hostBytes).get(detailBytes);
        ClientEndpointHint leaderHint = null;
        if ((flags & FLAG_LEADER_HINT) != 0) {
            UUID nodeId = nodeMost == 0 && nodeLeast == 0 ? null : new UUID(nodeMost, nodeLeast);
            leaderHint = new ClientEndpointHint(new String(hostBytes, StandardCharsets.UTF_8), port, nodeId);
        } else if (hostLength != 0 || port != 0 || nodeMost != 0 || nodeLeast != 0) {
            throw invalid();
        }
        return new ClientWriteResponse(
                ClientStatus.values()[statusOrdinal],
                commandId,
                operationCount,
                firstSequence,
                lastSequence,
                leaderHint,
                new String(detailBytes, StandardCharsets.UTF_8));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid CLIENT_WRITE response body");
    }
}
