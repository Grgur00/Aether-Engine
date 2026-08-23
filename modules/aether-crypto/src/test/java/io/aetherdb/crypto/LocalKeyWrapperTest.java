package io.aetherdb.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

final class LocalKeyWrapperTest {
    private static final byte[] WRAPPING_KEY = new byte[32];
    private static final byte[] DATA_KEY = new byte[32];

    @Test
    void wrapsAndUnwrapsKeyMaterial() {
        LocalKeyWrapper wrapper = new LocalKeyWrapper("local:test", WRAPPING_KEY);

        WrappedKey wrapped = wrapper.wrap(3, DATA_KEY, "database-master-key");

        assertThat(wrapped.wrappingKeyId()).isEqualTo("local:test");
        assertThat(wrapped.keyEpoch()).isEqualTo(3);
        assertThat(wrapper.unwrap(wrapped, "database-master-key")).isEqualTo(DATA_KEY);
    }

    @Test
    void rejectsWrongPurpose() {
        LocalKeyWrapper wrapper = new LocalKeyWrapper("local:test", WRAPPING_KEY);
        WrappedKey wrapped = wrapper.wrap(3, DATA_KEY, "database-master-key");

        assertThatThrownBy(() -> wrapper.unwrap(wrapped, "file-encryption-key"))
                .isInstanceOf(AetherDecryptionException.class);
    }

    @Test
    void rejectsWrongWrappingKeyId() {
        LocalKeyWrapper wrapper = new LocalKeyWrapper("local:test", WRAPPING_KEY);
        LocalKeyWrapper other = new LocalKeyWrapper("local:other", WRAPPING_KEY);
        WrappedKey wrapped = wrapper.wrap(3, DATA_KEY, "database-master-key");

        assertThatThrownBy(() -> other.unwrap(wrapped, "database-master-key"))
                .isInstanceOf(AetherDecryptionException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void corruptionMutatorDrivesWrappedKeyUnwrapFailure() {
        LocalKeyWrapper wrapper = new LocalKeyWrapper("local:test", WRAPPING_KEY);
        WrappedKey wrapped = wrapper.wrap(3, DATA_KEY, "database-master-key");
        byte[] corruptCiphertext =
                CorruptionMutator.apply(
                        wrapped.envelope().ciphertext(), CorruptionPlan.flipBit(0, 0));
        WrappedKey corrupted =
                new WrappedKey(
                        wrapped.wrappingKeyId(),
                        wrapped.keyEpoch(),
                        new AeadEnvelope(
                                wrapped.envelope().version(),
                                wrapped.envelope().keyEpoch(),
                                wrapped.envelope().nonce(),
                                corruptCiphertext));

        assertThatThrownBy(() -> wrapper.unwrap(corrupted, "database-master-key"))
                .isInstanceOf(AetherDecryptionException.class);
    }
}
