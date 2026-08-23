package io.aetherdb.io;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Operator and binary capabilities used by backup restore preflight.
 *
 * @param mode restore identity policy
 * @param allowNonEmptyTarget whether restore may write into a non-empty target directory
 * @param supportedFormatVersion maximum object format version supported by this binary
 * @param availableEncryptionKeyEpochs key epochs available to the restore process
 * @param existingDatabaseId existing target database id, required when preserving identity
 * @param existingClusterId existing cluster id, required for member replacement
 */
public record BackupRestorePreflightOptions(
        BackupRestoreMode mode,
        boolean allowNonEmptyTarget,
        int supportedFormatVersion,
        Set<Long> availableEncryptionKeyEpochs,
        UUID existingDatabaseId,
        UUID existingClusterId) {
    public BackupRestorePreflightOptions {
        Objects.requireNonNull(mode, "mode");
        if (supportedFormatVersion <= 0)
            throw new IllegalArgumentException("supported format version must be positive");
        availableEncryptionKeyEpochs =
                availableEncryptionKeyEpochs == null ? Set.of() : Set.copyOf(availableEncryptionKeyEpochs);
        for (Long epoch : availableEncryptionKeyEpochs)
            if (epoch == null || epoch <= 0)
                throw new IllegalArgumentException("invalid available encryption key epoch");
    }
}
