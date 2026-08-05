package io.aetherdb.api.typed;

import java.util.UUID;

/** Terminal or uncertain outcome of a typed write command. */
public sealed interface TypedWriteResult
        permits TypedWriteResult.Applied, TypedWriteResult.Rejected, TypedWriteResult.Indeterminate {
    /** Returns the idempotency identity assigned to the command.
     * @return command UUID */
    UUID commandId();

    /**
     * Successfully applied write.
     * @param commandId command identity
     * @param operationCount number of applied mutations
     * @param firstSequence first allocated sequence
     * @param lastSequence last allocated sequence
     */
    record Applied(UUID commandId, int operationCount, long firstSequence, long lastSequence)
            implements TypedWriteResult {}

    /**
     * Write rejected before an uncertain commit point.
     * @param commandId command identity
     * @param reason stable rejection reason
     * @param retryable whether retrying may succeed
     */
    record Rejected(UUID commandId, String reason, boolean retryable) implements TypedWriteResult {}

    /**
     * Write whose final commit status cannot be determined by the caller.
     * @param commandId command identity
     * @param stage stage at which certainty was lost
     * @param retryInstructions safe recovery guidance
     */
    record Indeterminate(UUID commandId, String stage, String retryInstructions) implements TypedWriteResult {}
}
