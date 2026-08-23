package io.aetherdb.format.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class AetherFormatCatalogTest {
    @Test
    void includesCoreStorageAndWireFormats() {
        AetherFormatCatalog catalog = AetherFormatCatalog.current();

        assertThat(catalog.require("aether.wal_segment.v1").magic()).isEqualTo("AETHWAL1");
        assertThat(catalog.require("aether.sstable.v1").magic()).isEqualTo("AETHSST1");
        assertThat(catalog.require("aether.sstable_block_envelope.v1").headerBytes())
                .isEqualTo(8);
        assertThat(catalog.require("aether.sstable_restart_block.v1").headerBytes())
                .isEqualTo(4);
        assertThat(catalog.require("aether.sstable_block_handle.v1").headerBytes()).isEqualTo(16);
        assertThat(catalog.require("aether.sstable_bloom.v1").headerBytes()).isEqualTo(24);
        assertThat(catalog.require("aether.manifest.v1").magic()).isEqualTo("AETHMAN1");
        assertThat(catalog.require("aether.current.v1").magic()).isEqualTo("AETHCUR1");
        assertThat(catalog.require("aether.wal_segment.v1").checksum())
                .isEqualTo(ChecksumPolicy.MASKED_CRC32C);
        assertThat(catalog.require("aether.rpc_frame.v1").byteOrder())
                .isEqualTo(ByteOrderPolicy.BIG_ENDIAN);
        assertThat(catalog.require("aether.security_metadata.v1").checksum())
                .isEqualTo(ChecksumPolicy.AES_GCM_TAG);
    }

    @Test
    void rejectsInvalidFormatIds() {
        assertThatThrownBy(
                        () ->
                                new FormatDescriptor(
                                        "wal.v1",
                                        FormatKind.STORAGE_FILE,
                                        "WAL",
                                        1,
                                        1,
                                        ByteOrderPolicy.LITTLE_ENDIAN,
                                        ChecksumPolicy.NONE,
                                        CompatibilityPolicy.EXACT_VERSION_ONLY,
                                        "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format id");
    }

    @Test
    void unknownFormatFailsClosed() {
        assertThatThrownBy(() -> AetherFormatCatalog.current().require("aether.missing.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown format");
    }

    @Test
    void goldenFixturesReferenceKnownFormats() {
        AetherFormatCatalog formats = AetherFormatCatalog.current();
        FormatGoldenFixtureCatalog fixtures = FormatGoldenFixtureCatalog.current(formats);

        FormatGoldenFixture fixture =
                fixtures.require("aether.wal_segment.v1", "canonical-header-v1");

        assertThat(formats.require(fixture.formatId()).headerBytes()).isEqualTo(96);
        assertThat(fixture.byteLength()).isEqualTo(32 * 1024);
        assertThat(fixture.sha256Hex()).matches("[0-9a-f]{64}");
        assertThat(fixtures.require("aether.sstable.v1", "canonical-header-region-v1")
                        .byteLength())
                .isEqualTo(4_096);
        assertThat(
                        fixtures.require(
                                        "aether.sstable_block_envelope.v1",
                                        "canonical-data-block-envelope-v1")
                                .byteLength())
                .isEqualTo(15);
        assertThat(
                        fixtures.require(
                                        "aether.sstable_restart_block.v1",
                                        "canonical-four-entry-block-v1")
                                .byteLength())
                .isEqualTo(40);
        assertThat(fixtures.require("aether.sstable_block_handle.v1", "canonical-offset-length-v1")
                        .byteLength())
                .isEqualTo(16);
        assertThat(fixtures.require("aether.sstable_bloom.v1", "canonical-three-key-filter-v1")
                        .byteLength())
                .isEqualTo(32);
        assertThat(fixtures.require("aether.manifest.v1", "canonical-header-region-v1")
                        .byteLength())
                .isEqualTo(4_096);
        assertThat(fixtures.require("aether.manifest.v1", "canonical-snapshot-record-v1")
                        .byteLength())
                .isEqualTo(256);
        assertThat(fixtures.require("aether.current.v1", "canonical-pointer-v1").byteLength())
                .isEqualTo(128);
        assertThat(fixtures.require("aether.rpc_frame.v1", "canonical-request-frame-v1")
                        .byteLength())
                .isEqualTo(67);
    }
}
