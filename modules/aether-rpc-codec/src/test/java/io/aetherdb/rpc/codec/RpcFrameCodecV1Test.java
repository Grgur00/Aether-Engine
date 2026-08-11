package io.aetherdb.rpc.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

final class RpcFrameCodecV1Test {
    private static final UUID INVOCATION = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void exactFrameRoundTripUses64ByteHeaderAndDetectsCorruption() {
        byte[] payload = {1, 2, 3};
        RpcFrame frame =
                new RpcFrame(
                        new RpcFrameHeaderV1(
                                RpcFrameType.REQUEST, 3, 1, 42, 3, 3, 0, 5000, 0, INVOCATION),
                        payload);
        byte[] encoded = RpcFrameCodecV1.encode(frame);
        assertThat(encoded).hasSize(67);
        RpcFrame decoded = RpcFrameCodecV1.decode(encoded);
        assertThat(decoded.header()).isEqualTo(frame.header());
        assertThat(decoded.payload()).containsExactly(payload);
        encoded[66] ^= 1;
        assertThatThrownBy(() -> RpcFrameCodecV1.decode(encoded))
                .isInstanceOf(RpcProtocolException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void fragmentationAndAssemblyPreserveMessageExactly() {
        byte[] message = new byte[2_500];
        for (int i = 0; i < message.length; i++) message[i] = (byte) i;
        List<RpcFrame> frames =
                RpcMessageFragmenter.fragment(
                        RpcFrameType.REQUEST, 1, 7, INVOCATION, 1000, message, 1024);
        assertThat(frames).hasSize(3);
        RpcMessageAssembler assembler = new RpcMessageAssembler(4_000);
        byte[] completed = null;
        for (RpcFrame frame : frames) completed = assembler.accept(frame);
        assertThat(completed).containsExactly(message);
    }

    @Test
    void assemblerRejectsNoncontiguousFragmentsBeforeCompletion() {
        List<RpcFrame> frames =
                RpcMessageFragmenter.fragment(
                        RpcFrameType.RESPONSE, 2, 0, INVOCATION, 0, new byte[10], 5);
        RpcFrame second = frames.get(1);
        RpcFrameHeaderV1 header = second.header();
        RpcFrame invalid =
                new RpcFrame(
                        new RpcFrameHeaderV1(
                                header.type(),
                                header.flags(),
                                header.streamId(),
                                header.code(),
                                header.payloadLength(),
                                header.messageLength(),
                                6,
                                0,
                                0,
                                header.invocationId()),
                        second.payload());
        RpcMessageAssembler assembler = new RpcMessageAssembler(20);
        assertThat(assembler.accept(frames.get(0))).isNull();
        assertThatThrownBy(() -> assembler.accept(invalid))
                .isInstanceOf(RpcProtocolException.class)
                .hasMessageContaining("noncontiguous");
    }

    @Test
    void streamAllocatorUsesRoleParityWithoutReuse() {
        RpcStreamIdAllocator dialer = new RpcStreamIdAllocator(RpcStreamIdAllocator.Role.DIALER);
        RpcStreamIdAllocator acceptor =
                new RpcStreamIdAllocator(RpcStreamIdAllocator.Role.ACCEPTOR);
        assertThat(List.of(dialer.nextId(), dialer.nextId())).containsExactly(1L, 3L);
        assertThat(List.of(acceptor.nextId(), acceptor.nextId())).containsExactly(2L, 4L);
    }

    @Test
    void helloIsExactAndChecksumProtected() {
        RpcHelloV1 hello =
                new RpcHelloV1(
                        RpcHelloV1.Role.DIALER,
                        UUID.fromString("11111111-1111-1111-8111-111111111111"),
                        UUID.fromString("22222222-2222-2222-8222-222222222222"),
                        UUID.fromString("33333333-3333-3333-8333-333333333333"),
                        7,
                        1024 * 1024,
                        16 * 1024 * 1024,
                        1024,
                        64 * 1024 * 1024,
                        30_000,
                        10_000,
                        1,
                        0,
                        0,
                        21);
        byte[] encoded = hello.encode();
        assertThat(encoded).hasSize(192);
        assertThat(RpcHelloV1.decode(encoded)).isEqualTo(hello);
        encoded[170] = 1;
        assertThatThrownBy(() -> RpcHelloV1.decode(encoded))
                .isInstanceOf(RpcProtocolException.class);
    }

    @Test
    void incrementalDecoderHandlesEverySingleByteSplitAndCoalescedFrames() {
        RpcFrame first =
                RpcMessageFragmenter.fragment(
                                RpcFrameType.REQUEST,
                                1,
                                7,
                                INVOCATION,
                                5000,
                                new byte[] {1, 2, 3},
                                1024)
                        .get(0);
        RpcFrame second =
                RpcMessageFragmenter.fragment(
                                RpcFrameType.RESPONSE, 1, 0, INVOCATION, 0, new byte[] {4, 5}, 1024)
                        .get(0);
        byte[] firstBytes = RpcFrameCodecV1.encode(first),
                secondBytes = RpcFrameCodecV1.encode(second);
        byte[] wire = new byte[firstBytes.length + secondBytes.length];
        System.arraycopy(firstBytes, 0, wire, 0, firstBytes.length);
        System.arraycopy(secondBytes, 0, wire, firstBytes.length, secondBytes.length);
        RpcFrameDecoder decoder = new RpcFrameDecoder(1024);
        java.util.ArrayList<RpcFrame> decoded = new java.util.ArrayList<>();

        for (byte value : wire) decoded.addAll(decoder.feed(ByteBuffer.wrap(new byte[] {value})));

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).header()).isEqualTo(first.header());
        assertThat(decoded.get(0).payload()).isEqualTo(first.payload());
        assertThat(decoded.get(1).header()).isEqualTo(second.header());
        assertThat(decoded.get(1).payload()).isEqualTo(second.payload());
        assertThat(decoder.hasPartialFrame()).isFalse();
        assertThat(new RpcFrameDecoder(1024).feed(ByteBuffer.wrap(wire))).hasSize(2);
    }

    @Test
    void incrementalDecoderRejectsDeclaredPayloadBeforeAllocatingIt() {
        RpcFrame frame =
                RpcMessageFragmenter.fragment(
                                RpcFrameType.REQUEST, 1, 7, INVOCATION, 5000, new byte[2048], 2048)
                        .get(0);
        byte[] encoded = RpcFrameCodecV1.encode(frame);
        RpcFrameDecoder decoder = new RpcFrameDecoder(1024);

        assertThatThrownBy(() -> decoder.feed(ByteBuffer.wrap(encoded, 0, 64)))
                .isInstanceOf(RpcProtocolException.class)
                .hasMessageContaining("negotiated payload limit");
        assertThat(decoder.retainedBytes()).isEqualTo(64);
    }
}
