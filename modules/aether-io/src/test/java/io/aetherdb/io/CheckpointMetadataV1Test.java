package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckpointMetadataV1Test {
    @Test void exactFormatRoundTripsAndRejectsCorruption() {
        byte[] fingerprint = new byte[32]; for (int index = 0; index < fingerprint.length; index++) fingerprint[index] = (byte) index;
        CheckpointMetadataV1 metadata = new CheckpointMetadataV1(
                UUID.fromString("3bd39131-3aca-4569-bfe5-cd7d5a1a3421"), 42, 100, 7, 7, 9, 3, 1234,
                fingerprint);
        byte[] encoded = metadata.encode();
        assertThat(encoded).hasSize(CheckpointMetadataV1.ENCODED_BYTES);
        CheckpointMetadataV1 decoded = CheckpointMetadataV1.decode(encoded);
        assertThat(decoded.databaseId()).isEqualTo(metadata.databaseId());
        assertThat(decoded.checkpointSequence()).isEqualTo(42);
        assertThat(decoded.compatibilityFingerprint()).isEqualTo(fingerprint);
        encoded[100] ^= 1;
        assertThatThrownBy(() -> CheckpointMetadataV1.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
    }
}
