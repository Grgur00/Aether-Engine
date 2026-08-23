package io.aetherdb.raft.storage;

import static org.assertj.core.api.Assertions.*;

import io.aetherdb.format.catalog.AetherFormatCatalog;
import io.aetherdb.format.catalog.FormatGoldenFixture;
import io.aetherdb.format.catalog.FormatGoldenFixtureCatalog;
import io.aetherdb.raft.api.*;
import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class RaftStorageFormatTest {
    static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            SESSION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void voteBodiesRoundTripAtExactSizes() {
        var request = canonicalRequest();
        byte[] encoded = VoteCodecV1.encodeRequest(request);
        assertThat(encoded).hasSize(128);
        var decoded = VoteCodecV1.decodeRequest(encoded);
        assertThat(decoded.kind()).isEqualTo(request.kind());
        assertThat(decoded.term()).isEqualTo(7);
        assertThat(decoded.lastEntryHash()).containsOnly(0);
        var response = canonicalResponse();
        assertThat(VoteCodecV1.decodeResponse(VoteCodecV1.encodeResponse(response)))
                .isEqualTo(response);
    }

    @Test
    void voteRequestMatchesGoldenFixtureCatalog() {
        byte[] encoded = VoteCodecV1.encodeRequest(canonicalRequest());
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.raft_vote_request.v1", "canonical-request-vote-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        VoteRequest decoded = VoteCodecV1.decodeRequest(encoded);
        VoteRequest expected = canonicalRequest();
        assertThat(decoded.kind()).isEqualTo(expected.kind());
        assertThat(decoded.term()).isEqualTo(expected.term());
        assertThat(decoded.candidateId()).isEqualTo(expected.candidateId());
        assertThat(decoded.sessionId()).isEqualTo(expected.sessionId());
        assertThat(decoded.lastLogIndex()).isEqualTo(expected.lastLogIndex());
        assertThat(decoded.lastLogTerm()).isEqualTo(expected.lastLogTerm());
        assertThat(decoded.lastStateSequence()).isEqualTo(expected.lastStateSequence());
        assertThat(decoded.lastEntryHash()).isEqualTo(expected.lastEntryHash());
        assertThat(decoded.nonce()).isEqualTo(expected.nonce());
        assertThat(decoded.configurationVersion()).isEqualTo(expected.configurationVersion());
    }

    @Test
    void voteResponseMatchesGoldenFixtureCatalog() {
        byte[] encoded = VoteCodecV1.encodeResponse(canonicalResponse());
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.raft_vote_response.v1", "canonical-granted-vote-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(VoteCodecV1.decodeResponse(encoded)).isEqualTo(canonicalResponse());
    }

    @Test
    void stateSlotDetectsTornBytes() {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) 7);
        var state = canonicalState();
        byte[] slot = RaftStateSlotCodecV1.encode(SESSION, NODE, state, 4, fp, 123);
        assertThat(RaftStateSlotCodecV1.decode(slot, SESSION, NODE, fp)).isEqualTo(state);
        slot[60] ^= 1;
        assertThatThrownBy(() -> RaftStateSlotCodecV1.decode(slot, SESSION, NODE, fp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateSlotMatchesGoldenFixtureCatalog() {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) 7);
        byte[] encoded = RaftStateSlotCodecV1.encode(SESSION, NODE, canonicalState(), 4, fp, 123);
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.raft_state_slot.v1", "canonical-voted-state-slot-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(RaftStateSlotCodecV1.decode(encoded, SESSION, NODE, fp))
                .isEqualTo(canonicalState());
    }

    @Test
    void corruptionMutatorDrivesRaftVoteAndStateSlotFailures() {
        byte[] fp = new byte[32];
        Arrays.fill(fp, (byte) 7);
        var state = canonicalState();
        byte[] slot = RaftStateSlotCodecV1.encode(SESSION, NODE, state, 4, fp, 123);

        byte[] corruptSlot = CorruptionMutator.apply(slot, CorruptionPlan.flipBit(60, 0));
        assertThatThrownBy(() -> RaftStateSlotCodecV1.decode(corruptSlot, SESSION, NODE, fp))
                .isInstanceOf(IllegalArgumentException.class);

        var request = canonicalRequest();
        byte[] corruptRequest =
                CorruptionMutator.apply(
                        VoteCodecV1.encodeRequest(request),
                        CorruptionPlan.overwriteRange(0, 1, (byte) 7));
        assertThatThrownBy(() -> VoteCodecV1.decodeRequest(corruptRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static VoteRequest canonicalRequest() {
        return new VoteRequest(
                VoteKind.REQUEST_VOTE, 7, NODE, SESSION, 12, 6, 20, new byte[32], 99, 3);
    }

    private static VoteResponse canonicalResponse() {
        return new VoteResponse(
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
    }

    private static RaftPersistentState canonicalState() {
        return new RaftPersistentState(4, 9, Optional.of(NODE));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
