package io.aetherdb.api.typed;
import java.util.UUID;
public sealed interface TypedWriteResult permits TypedWriteResult.Applied,TypedWriteResult.Rejected,TypedWriteResult.Indeterminate{UUID commandId();record Applied(UUID commandId,int operationCount,long firstSequence,long lastSequence)implements TypedWriteResult{}record Rejected(UUID commandId,String reason,boolean retryable)implements TypedWriteResult{}record Indeterminate(UUID commandId,String stage,String retryInstructions)implements TypedWriteResult{}}
