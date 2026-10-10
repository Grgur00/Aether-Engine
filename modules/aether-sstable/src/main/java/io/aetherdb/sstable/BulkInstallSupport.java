package io.aetherdb.sstable;

import io.aetherdb.sstable.manifest.VersionSet;
import java.io.IOException;

/**
 * Internal cross-module bridge for EmptyStoreBulkLoader, not a supported application API.
 * Outputs are unpublished and MUST pass VersionSet inventory verification before installation.
 */
public final class BulkInstallSupport {
    private BulkInstallSupport() {}

    /** Builds only for a never-populated version; does not bypass manifest verification. */
    public static TableFileMetadata finishUnpublished(
            VersionSet versions, SSTableBuilder builder, SSTableFinishTrace trace) throws IOException {
        var current = versions.current();
        if (!current.allFiles().isEmpty() || current.lastAssignedSequence() != 0
                || current.persistedSequenceWatermark() != 0) {
            throw new IllegalStateException("deferred verification requires an empty-store bulk install");
        }
        return builder.finishForBulkInstall(trace);
    }
}
