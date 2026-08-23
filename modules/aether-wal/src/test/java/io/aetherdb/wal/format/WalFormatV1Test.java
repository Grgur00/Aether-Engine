package io.aetherdb.wal.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.format.catalog.AetherFormatCatalog;
import io.aetherdb.format.catalog.FormatGoldenFixture;
import io.aetherdb.format.catalog.FormatGoldenFixtureCatalog;
import io.aetherdb.reliability.CorruptionMutator;
import io.aetherdb.reliability.CorruptionPlan;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

class WalFormatV1Test {
    @Test
    void crcMaskRoundTrips() {
        byte[] bytes = "aether".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int crc = MaskedCrc32c.crc(bytes, 0, bytes.length);
        assertThat(MaskedCrc32c.unmask(MaskedCrc32c.mask(crc))).isEqualTo(crc);
    }

    @Test
    void segmentHeaderIsExactAndStrictlyValidated() {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        WalSegmentHeader expected = new WalSegmentHeader(id, 42, 41, 100, 1234);
        byte[] block = expected.encodeBlock();
        assertThat(block).hasSize(32 * 1024);
        assertThat(WalSegmentHeader.decode(block, id, 42)).isEqualTo(expected);
        block[76] = 1;
        assertThatThrownBy(() -> WalSegmentHeader.decode(block, id, 42))
                .isInstanceOf(WalCorruptionException.class);
    }

    @Test
    void segmentHeaderMatchesGoldenFixtureCatalog() {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        WalSegmentHeader expected = new WalSegmentHeader(id, 42, 41, 100, 1234);
        byte[] block = expected.encodeBlock();
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.wal_segment.v1", "canonical-header-v1");

        assertThat(block).hasSize(fixture.byteLength());
        assertThat(sha256Hex(block)).isEqualTo(fixture.sha256Hex());
        assertThat(WalSegmentHeader.decode(block, id, 42)).isEqualTo(expected);
    }

    @Test
    void logicalGroupMatchesGoldenFixtureCatalog() {
        WriteBatch batch = new WriteBatch().put(bytes("alpha"), bytes("one")).delete(bytes("beta"));
        byte[] encoded = WalLogicalGroupCodec.encode(batch, 101, 102);
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.wal_group.v1", "canonical-put-delete-group-v1");
        WalLogicalGroupCodec.DecodedGroup decoded = WalLogicalGroupCodec.decode(encoded);

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(decoded.firstSequence()).isEqualTo(101);
        assertThat(decoded.lastSequence()).isEqualTo(102);
        assertThat(decoded.mutations()).hasSize(2);
        assertThat(decoded.mutations().get(0).key()).isEqualTo(bytes("alpha"));
        assertThat(decoded.mutations().get(0).value()).isEqualTo(bytes("one"));
        assertThat(decoded.mutations().get(0).delete()).isFalse();
        assertThat(decoded.mutations().get(1).key()).isEqualTo(bytes("beta"));
        assertThat(decoded.mutations().get(1).value()).isEmpty();
        assertThat(decoded.mutations().get(1).delete()).isTrue();
    }

    @Test
    void logicalGroupRejectsMetadataAndOperationCorruption() {
        WriteBatch batch = new WriteBatch().put(bytes("alpha"), bytes("one")).delete(bytes("beta"));
        byte[] encoded = WalLogicalGroupCodec.encode(batch, 101, 102);

        byte[] corruptChecksum = encoded.clone();
        corruptChecksum[44] ^= 1;
        assertThatThrownBy(() -> WalLogicalGroupCodec.decode(corruptChecksum))
                .isInstanceOf(WalCorruptionException.class)
                .hasMessageContaining("metadata");

        byte[] corruptFlags = encoded.clone();
        corruptFlags[49] = 1;
        assertThatThrownBy(() -> WalLogicalGroupCodec.decode(corruptFlags))
                .isInstanceOf(WalCorruptionException.class)
                .hasMessageContaining("operation flags");
    }

    @Test
    void fragmentationNeverCrossesBlocksAndRoundTripsLargeRecords() {
        byte[] logical = new byte[200_000];
        new Random(7).nextBytes(logical);
        byte[] physical = WalFragmentCodec.fragment(logical, WalFormatV1.HEADER_BLOCK_BYTES, 1);
        assertThat(WalFragmentCodec.reassemble(physical, WalFormatV1.HEADER_BLOCK_BYTES))
                .containsExactly(logical);
        assertThat(WalFormatV1.estimateEndOffset(WalFormatV1.HEADER_BLOCK_BYTES, logical.length))
                .isEqualTo(WalFormatV1.HEADER_BLOCK_BYTES + physical.length);
        physical[20] ^= 1;
        assertThatThrownBy(
                        () -> WalFragmentCodec.reassemble(physical, WalFormatV1.HEADER_BLOCK_BYTES))
                .isInstanceOf(WalCorruptionException.class);
    }

    @Test
    void forensicRecoveryReturnsOnlyCompleteGroupsBeforeCorruption() {
        byte[] first = "first-group".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second-group".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] firstPhysical = WalFragmentCodec.fragment(first, WalFormatV1.HEADER_BLOCK_BYTES, 1);
        byte[] secondPhysical =
                WalFragmentCodec.fragment(
                        second, WalFormatV1.HEADER_BLOCK_BYTES + firstPhysical.length, 2);
        byte[] physical = new byte[firstPhysical.length + secondPhysical.length];
        System.arraycopy(firstPhysical, 0, physical, 0, firstPhysical.length);
        System.arraycopy(secondPhysical, 0, physical, firstPhysical.length, secondPhysical.length);
        physical[firstPhysical.length + WalFormatV1.FRAGMENT_HEADER_BYTES] ^= 1;

        WalFragmentCodec.PrefixRecovery recovery =
                WalFragmentCodec.recoverPrefix(physical, WalFormatV1.HEADER_BLOCK_BYTES);

        assertThat(recovery.records()).containsExactly(first);
        assertThat(recovery.validEndOffset())
                .isEqualTo(WalFormatV1.HEADER_BLOCK_BYTES + firstPhysical.length);
        assertThat(recovery.issue()).contains("checksum").contains("offset");
    }

    @Test
    void forensicRecoveryDoesNotExposeAnIncompleteLogicalRecord() {
        byte[] logical = new byte[100_000];
        new Random(17).nextBytes(logical);
        byte[] physical = WalFragmentCodec.fragment(logical, WalFormatV1.HEADER_BLOCK_BYTES, 1);

        WalFragmentCodec.PrefixRecovery recovery =
                WalFragmentCodec.recoverPrefix(
                        java.util.Arrays.copyOf(physical, physical.length - 10),
                        WalFormatV1.HEADER_BLOCK_BYTES);

        assertThat(recovery.records()).isEmpty();
        assertThat(recovery.validEndOffset()).isEqualTo(WalFormatV1.HEADER_BLOCK_BYTES);
        assertThat(recovery.hasIssue()).isTrue();
    }

    @Test
    void corruptionMutatorDrivesWalChecksumAndTailRecoveryCases() {
        byte[] first = "first-group".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] second = "second-group".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] firstPhysical = WalFragmentCodec.fragment(first, WalFormatV1.HEADER_BLOCK_BYTES, 1);
        byte[] secondPhysical =
                WalFragmentCodec.fragment(
                        second, WalFormatV1.HEADER_BLOCK_BYTES + firstPhysical.length, 2);
        byte[] physical = new byte[firstPhysical.length + secondPhysical.length];
        System.arraycopy(firstPhysical, 0, physical, 0, firstPhysical.length);
        System.arraycopy(secondPhysical, 0, physical, firstPhysical.length, secondPhysical.length);

        byte[] checksumCorrupt =
                CorruptionMutator.apply(
                        physical,
                        CorruptionPlan.flipBit(
                                firstPhysical.length + WalFormatV1.FRAGMENT_HEADER_BYTES, 0));
        WalFragmentCodec.PrefixRecovery corruptRecovery =
                WalFragmentCodec.recoverPrefix(checksumCorrupt, WalFormatV1.HEADER_BLOCK_BYTES);
        assertThat(corruptRecovery.records()).containsExactly(first);
        assertThat(corruptRecovery.issue()).contains("checksum");

        byte[] tornTail =
                CorruptionMutator.apply(
                        physical, CorruptionPlan.tornWritePrefix(physical.length - 3));
        WalFragmentCodec.PrefixRecovery tornRecovery =
                WalFragmentCodec.recoverPrefix(tornTail, WalFormatV1.HEADER_BLOCK_BYTES);
        assertThat(tornRecovery.records()).containsExactly(first);
        assertThat(tornRecovery.hasIssue()).isTrue();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
