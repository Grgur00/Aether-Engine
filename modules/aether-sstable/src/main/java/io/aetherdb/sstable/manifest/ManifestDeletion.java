package io.aetherdb.sstable.manifest;

/** Identifies one table removed from a version.
 * @param fileNumber durable table file number
 * @param level source level containing the live table */
public record ManifestDeletion(long fileNumber, int level) {
    /** Validates the durable table identity. */
    public ManifestDeletion {
        if (fileNumber <= 0 || level < 0 || level > 6) throw new IllegalArgumentException("invalid manifest deletion");
    }
}
