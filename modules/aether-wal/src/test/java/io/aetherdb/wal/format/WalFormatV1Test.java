package io.aetherdb.wal.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalFormatV1Test {
    @Test void crcMaskRoundTrips() {
        byte[] bytes = "aether".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int crc = MaskedCrc32c.crc(bytes, 0, bytes.length);
        assertThat(MaskedCrc32c.unmask(MaskedCrc32c.mask(crc))).isEqualTo(crc);
    }

    @Test void segmentHeaderIsExactAndStrictlyValidated() {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        WalSegmentHeader expected = new WalSegmentHeader(id, 42, 41, 100, 1234);
        byte[] block = expected.encodeBlock();
        assertThat(block).hasSize(32 * 1024);
        assertThat(WalSegmentHeader.decode(block, id, 42)).isEqualTo(expected);
        block[76] = 1;
        assertThatThrownBy(() -> WalSegmentHeader.decode(block, id, 42)).isInstanceOf(WalCorruptionException.class);
    }

    @Test void fragmentationNeverCrossesBlocksAndRoundTripsLargeRecords() {
        byte[] logical = new byte[200_000]; new Random(7).nextBytes(logical);
        byte[] physical = WalFragmentCodec.fragment(logical, WalFormatV1.HEADER_BLOCK_BYTES, 1);
        assertThat(WalFragmentCodec.reassemble(physical, WalFormatV1.HEADER_BLOCK_BYTES)).containsExactly(logical);
        assertThat(WalFormatV1.estimateEndOffset(WalFormatV1.HEADER_BLOCK_BYTES, logical.length))
                .isEqualTo(WalFormatV1.HEADER_BLOCK_BYTES + physical.length);
        physical[20] ^= 1;
        assertThatThrownBy(() -> WalFragmentCodec.reassemble(physical, WalFormatV1.HEADER_BLOCK_BYTES))
                .isInstanceOf(WalCorruptionException.class);
    }
}
