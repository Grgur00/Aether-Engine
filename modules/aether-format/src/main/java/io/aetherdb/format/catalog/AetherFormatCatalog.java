package io.aetherdb.format.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/** Initial registry for Aether's known persisted and wire formats. */
public final class AetherFormatCatalog {
    private final Map<String, FormatDescriptor> descriptors;

    private AetherFormatCatalog(Map<String, FormatDescriptor> descriptors) {
        this.descriptors = Map.copyOf(descriptors);
    }

    public static AetherFormatCatalog current() {
        LinkedHashMap<String, FormatDescriptor> descriptors = new LinkedHashMap<>();
        add(
                descriptors,
                "aether.native_record.v1",
                FormatKind.STORAGE_RECORD,
                "AENR",
                1,
                16,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.FORMAT_SPECIFIC,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-memory");
        add(
                descriptors,
                "aether.internal_key.v1",
                FormatKind.STORAGE_RECORD,
                "implicit",
                1,
                0,
                ByteOrderPolicy.MIXED,
                ChecksumPolicy.NONE,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.wal_segment.v1",
                FormatKind.STORAGE_FILE,
                "AETHWAL1",
                1,
                96,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-wal");
        add(
                descriptors,
                "aether.wal_fragment.v1",
                FormatKind.STORAGE_RECORD,
                "fragment-type",
                1,
                16,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-wal");
        add(
                descriptors,
                "aether.wal_group.v1",
                FormatKind.STORAGE_RECORD,
                "AETHGRP1",
                1,
                48,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-wal");
        add(
                descriptors,
                "aether.sstable.v1",
                FormatKind.STORAGE_FILE,
                "AETHSST1",
                1,
                128,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.sstable_block_envelope.v1",
                FormatKind.STORAGE_RECORD,
                "block-trailer",
                1,
                8,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.sstable_restart_block.v1",
                FormatKind.STORAGE_RECORD,
                "restart-suffix",
                1,
                4,
                ByteOrderPolicy.MIXED,
                ChecksumPolicy.NONE,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.sstable_block_handle.v1",
                FormatKind.STORAGE_RECORD,
                "implicit",
                1,
                16,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.NONE,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.sstable_footer.v1",
                FormatKind.STORAGE_RECORD,
                "AETHFTR1",
                1,
                128,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.sstable_bloom.v1",
                FormatKind.STORAGE_RECORD,
                "bloom-v1",
                1,
                24,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.NONE,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.manifest.v1",
                FormatKind.METADATA_FILE,
                "AETHMAN1",
                1,
                64,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.current.v1",
                FormatKind.METADATA_FILE,
                "AETHCUR1",
                1,
                128,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-sstable");
        add(
                descriptors,
                "aether.database_identity.v1",
                FormatKind.METADATA_FILE,
                "AETHDBI1",
                1,
                128,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-io");
        add(
                descriptors,
                "aether.format_options.v1",
                FormatKind.METADATA_FILE,
                "AETHFMT1",
                1,
                512,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-io");
        add(
                descriptors,
                "aether.checkpoint_metadata.v1",
                FormatKind.METADATA_FILE,
                "AETHCHK1",
                1,
                256,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-io");
        add(
                descriptors,
                "aether.rpc_frame.v1",
                FormatKind.WIRE_FRAME,
                "AERP",
                1,
                64,
                ByteOrderPolicy.BIG_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-rpc-codec");
        add(
                descriptors,
                "aether.rpc_hello.v1",
                FormatKind.WIRE_FRAME,
                "AEHL",
                1,
                192,
                ByteOrderPolicy.BIG_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-rpc-codec");
        add(
                descriptors,
                "aether.replicated_log_segment.v1",
                FormatKind.STORAGE_FILE,
                "AETHRSG1",
                1,
                192,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-replicated-log");
        add(
                descriptors,
                "aether.replicated_log_entry.v1",
                FormatKind.STORAGE_RECORD,
                "AERE",
                1,
                192,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-replicated-log");
        add(
                descriptors,
                "aether.raft_vote_request.v1",
                FormatKind.WIRE_FRAME,
                "AEVR",
                1,
                128,
                ByteOrderPolicy.BIG_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-raft-storage");
        add(
                descriptors,
                "aether.raft_vote_response.v1",
                FormatKind.WIRE_FRAME,
                "AEVP",
                1,
                96,
                ByteOrderPolicy.BIG_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-raft-storage");
        add(
                descriptors,
                "aether.raft_state_slot.v1",
                FormatKind.METADATA_FILE,
                "AETHRFS1",
                1,
                512,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.MASKED_CRC32C,
                CompatibilityPolicy.EXACT_VERSION_ONLY,
                "aether-raft-storage");
        add(
                descriptors,
                "aether.schema_lock.v1",
                FormatKind.SCHEMA_LOCK,
                "json",
                1,
                0,
                ByteOrderPolicy.TEXT_JSON,
                ChecksumPolicy.FORMAT_SPECIFIC,
                CompatibilityPolicy.TEXT_SCHEMA_COMPATIBLE,
                "aether-codec-processor");
        add(
                descriptors,
                "aether.security_metadata.v1",
                FormatKind.SECURITY_METADATA,
                "AESEC1",
                1,
                64,
                ByteOrderPolicy.LITTLE_ENDIAN,
                ChecksumPolicy.AES_GCM_TAG,
                CompatibilityPolicy.LENGTH_DELIMITED_OPTIONAL_FIELDS,
                "aether-security");
        add(
                descriptors,
                "aether.backup_manifest.v1",
                FormatKind.BACKUP_OBJECT,
                "json",
                1,
                0,
                ByteOrderPolicy.TEXT_JSON,
                ChecksumPolicy.SHA256_MANIFEST,
                CompatibilityPolicy.TEXT_SCHEMA_COMPATIBLE,
                "aether-tools");
        return new AetherFormatCatalog(descriptors);
    }

    public Map<String, FormatDescriptor> descriptors() {
        return descriptors;
    }

    public FormatDescriptor require(String id) {
        FormatDescriptor descriptor = descriptors.get(id);
        if (descriptor == null) throw new IllegalArgumentException("unknown format: " + id);
        return descriptor;
    }

    private static void add(
            Map<String, FormatDescriptor> descriptors,
            String id,
            FormatKind kind,
            String magic,
            int version,
            int headerBytes,
            ByteOrderPolicy byteOrder,
            ChecksumPolicy checksum,
            CompatibilityPolicy compatibility,
            String ownerModule) {
        FormatDescriptor previous =
                descriptors.put(
                        id,
                        new FormatDescriptor(
                                id,
                                kind,
                                magic,
                                version,
                                headerBytes,
                                byteOrder,
                                checksum,
                                compatibility,
                                ownerModule));
        if (previous != null) throw new IllegalStateException("duplicate format id: " + id);
    }
}
