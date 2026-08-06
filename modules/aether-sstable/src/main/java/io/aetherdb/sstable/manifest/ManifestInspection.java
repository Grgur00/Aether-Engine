package io.aetherdb.sstable.manifest;

import java.nio.file.Path;

/** Read-only manifest recovery result for operational inspection.
 * @param manifestPath authoritative manifest path
 * @param header validated fixed header
 * @param version terminal complete version
 * @param recordCount complete physical record count
 * @param physicalBytes manifest file bytes
 * @param incompleteTailBytes trailing bytes that form no complete record */
public record ManifestInspection(Path manifestPath, ManifestHeaderV1 header, Version version,
                                 long recordCount, long physicalBytes, long incompleteTailBytes) {
    /** Validates nonnegative accounting and required values. */
    public ManifestInspection {
        if (manifestPath == null || header == null || version == null || recordCount <= 0
                || physicalBytes < ManifestHeaderV1.HEADER_REGION_BYTES || incompleteTailBytes < 0
                || incompleteTailBytes > physicalBytes) throw new IllegalArgumentException("invalid manifest inspection");
    }
}
