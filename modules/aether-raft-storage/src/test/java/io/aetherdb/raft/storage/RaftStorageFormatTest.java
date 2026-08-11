package io.aetherdb.raft.storage;

import static org.assertj.core.api.Assertions.*;

import io.aetherdb.raft.api.*;

import org.junit.jupiter.api.Test;

import java.util.*;

final class RaftStorageFormatTest {
    static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void voteBodiesRoundTripAtExactSizes() {
        var request =
                new VoteRequest(
                        VoteKind.REQUEST_VOTE, 7, NODE, SESSION, 12, 6, 20, new byte[32], 99, 3);
        byte[] encoded = VoteCodecV1.encodeRequest(request);
        assertThat(encoded).hasSize(128);
        var decoded = VoteCodecV1.decodeRequest(encoded);
        assertThat(decoded.kind()).isEqualTo(request.kind());
        assertThat(decoded.term()).isEqualTo(7);
        assertThat(decoded.lastEntryHash()).containsOnly(0);
        var response =
                new VoteResponse(
                        VoteKind.REQUEST_VOTE,
                        true,
                        VoteReason.GRANTED,
                        7,
                        NODE,
                        SESSION,
                        99,
                        12,
                        6,
                        3);
        assertThat(VoteCodecV1.decodeResponse(VoteCodecV1.encodeResponse(response)))
                .isEqualTo(response);
    }

    @Test
    void stateSlotDetectsTornBytes() {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) 7);
        var state = new RaftPersistentState(4, 9, Optional.of(NODE));
        byte[] slot = RaftStateSlotCodecV1.encode(SESSION, NODE, state, 4, fp, 123);
        assertThat(RaftStateSlotCodecV1.decode(slot, SESSION, NODE, fp)).isEqualTo(state);
        slot[60] ^= 1;
        assertThatThrownBy(() -> RaftStateSlotCodecV1.decode(slot, SESSION, NODE, fp))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
