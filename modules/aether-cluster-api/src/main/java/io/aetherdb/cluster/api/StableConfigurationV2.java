package io.aetherdb.cluster.api;

import java.util.*;

public final class StableConfigurationV2 implements StableConfiguration {
 private final StableConfigurationV1 base; private final UUID transitionId; private final int changedMembers;
 public StableConfigurationV2(UUID clusterId,long version,UUID configurationId,byte[] previousHash,UUID completedTransitionId,List<ClusterMember> members,long created,int changedMembers,byte[] hash){base=new StableConfigurationV1(clusterId,version,configurationId,previousHash,members,created,hash);if(completedTransitionId==null||changedMembers<0||changedMembers>16)throw new IllegalArgumentException("invalid transition metadata");transitionId=completedTransitionId;this.changedMembers=changedMembers;}
 public UUID completedTransitionId(){return transitionId;}public int changedMemberCount(){return changedMembers;}public UUID clusterId(){return base.clusterId();}public long stateVersion(){return base.stateVersion();}public UUID configurationId(){return base.configurationId();}public byte[] previousHash(){return base.previousHash();}public List<ClusterMember> members(){return base.members();}public long creationEpochMillis(){return base.creationEpochMillis();}public Set<UUID> voters(){return base.voters();}public byte[] hash(){return base.hash();}
}
