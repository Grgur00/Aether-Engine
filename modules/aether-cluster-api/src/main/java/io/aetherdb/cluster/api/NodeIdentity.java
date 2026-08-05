package io.aetherdb.cluster.api;
import java.util.UUID;
public record NodeIdentity(UUID clusterId,UUID nodeId,long creationEpochMillis,MemberRole initialRole,long generation){
 public NodeIdentity{if(clusterId==null||nodeId==null||clusterId.equals(new UUID(0,0))||nodeId.equals(new UUID(0,0))||creationEpochMillis<0||initialRole==null||generation!=1)throw new IllegalArgumentException("invalid node identity");}
}
