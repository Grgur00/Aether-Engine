package io.aetherdb.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class AetherAeadTest {
    private static final byte[] KEY = new byte[32];
    private static final byte[] NONCE = new byte[12];
    private static final byte[] AAD = "wal:1:offset:32768".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsWithEnvelopeEncoding() {
        byte[] plaintext = "durable bytes".getBytes(StandardCharsets.UTF_8);

        AeadEnvelope envelope = AetherAead.encryptWithNonceForTest(KEY, 7, NONCE, plaintext, AAD);
        AeadEnvelope decoded = AeadEnvelope.decode(envelope.encode());

        assertThat(decoded.keyEpoch()).isEqualTo(7);
        assertThat(AetherAead.decrypt(KEY, decoded, AAD)).isEqualTo(plaintext);
    }

    @Test
    void rejectsAadMismatch() {
        AeadEnvelope envelope =
                AetherAead.encryptWithNonceForTest(
                        KEY, 1, NONCE, "secret".getBytes(StandardCharsets.UTF_8), AAD);

        assertThatThrownBy(
                        () ->
                                AetherAead.decrypt(
                                        KEY,
                                        envelope,
                                        "sstable:2:block:0".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AetherDecryptionException.class);
    }

    @Test
    void rejectsCiphertextTamper() {
        AeadEnvelope envelope =
                AetherAead.encryptWithNonceForTest(
                        KEY, 1, NONCE, "secret".getBytes(StandardCharsets.UTF_8), AAD);
        byte[] tampered = envelope.ciphertext();
        tampered[0] ^= 1;

        assertThatThrownBy(
                        () ->
                                AetherAead.decrypt(
                                        KEY,
                                        new AeadEnvelope(
                                                AeadEnvelope.VERSION,
                                                envelope.keyEpoch(),
                                                envelope.nonce(),
                                                tampered),
                                        AAD))
                .isInstanceOf(AetherDecryptionException.class);
    }

    @Test
    void corruptionMutatorDrivesEnvelopeDecodeAndDecryptFailures() {
        AeadEnvelope envelope =
                AetherAead.encryptWithNonceForTest(
                        KEY, 1, NONCE, "secret".getBytes(StandardCharsets.UTF_8), AAD);

        byte[] badMagic = CorruptionMutator.apply(envelope.encode(), CorruptionPlan.flipBit(0, 0));
        assertThatThrownBy(() -> AeadEnvelope.decode(badMagic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");

        byte[] badCiphertext =
                CorruptionMutator.apply(envelope.ciphertext(), CorruptionPlan.flipBit(0, 0));
        AeadEnvelope corrupted =
                new AeadEnvelope(
                        envelope.version(), envelope.keyEpoch(), envelope.nonce(), badCiphertext);
        assertThatThrownBy(() -> AetherAead.decrypt(KEY, corrupted, AAD))
                .isInstanceOf(AetherDecryptionException.class);
    }

    @Test
    void rejectsInvalidKeyLength() {
        assertThatThrownBy(() -> AetherAead.encrypt(new byte[16], 1, new byte[0], AAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
