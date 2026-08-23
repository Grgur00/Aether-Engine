package io.aetherdb.client.api;

import java.util.List;
import java.util.UUID;

/**
 * Bounded ordered mutation batch submitted through the client protocol.
 *
 * @param configurationVersion caller's cluster configuration version
 * @param commandId client command identity, or the zero UUID when no deduplication is requested
 * @param deduplicated whether the server must deduplicate retries for the command identity
 * @param operations one to ten thousand ordered mutations
 */
public record ClientWriteRequest(
        long configurationVersion,
        UUID commandId,
        boolean deduplicated,
        List<ClientWriteOperation> operations) {
    /** Command identity marker for writes that do not request server deduplication. */
    public static final UUID NO_COMMAND_ID = new UUID(0, 0);

    /** Creates a non-deduplicated write request for existing embedded and v1 callers. */
    public ClientWriteRequest(long configurationVersion, List<ClientWriteOperation> operations) {
        this(configurationVersion, NO_COMMAND_ID, false, operations);
    }

    /** Copies operations and validates request bounds. */
    public ClientWriteRequest {
        if (commandId == null) throw new IllegalArgumentException("invalid client write");
        operations = List.copyOf(operations);
        if (configurationVersion < 0
                || operations.isEmpty()
                || operations.size() > 10_000
                || deduplicated && commandId.equals(NO_COMMAND_ID)
                || !deduplicated && !commandId.equals(NO_COMMAND_ID))
            throw new IllegalArgumentException("invalid client write");
    }

    /** Creates a retry-safe deduplicated write request with a caller-generated command ID. */
    public static ClientWriteRequest deduplicated(
            long configurationVersion, UUID commandId, List<ClientWriteOperation> operations) {
        return new ClientWriteRequest(configurationVersion, commandId, true, operations);
    }
}
