package io.aetherdb.cluster.api;

import java.util.*;

/** Stable configuration that records completion of a joint-consensus transition. */
public final class StableConfigurationV2 implements StableConfiguration {
 private final StableConfigurationV1 base; private final UUID transitionId; private final int changedMembers;
 /**
  * Creates a completed-transition configuration.
  * @param clusterId owning cluster
  * @param version state version
  * @param configurationId unique configuration identity
  * @param previousHash preceding configuration hash
  * @param completedTransitionId completed joint transition
  * @param members configured members
  * @param created creation time in epoch milliseconds
  * @param changedMembers number of changed members
  * @param hash canonical configuration hash
  */
 public StableConfigurationV2(UUID clusterId,long version,UUID configurationId,byte[] previousHash,UUID completedTransitionId,List<ClusterMember> members,long created,int changedMembers,byte[] hash){base=new StableConfigurationV1(clusterId,version,configurationId,previousHash,members,created,hash);if(completedTransitionId==null||changedMembers<0||changedMembers>16)throw new IllegalArgumentException("invalid transition metadata");transitionId=completedTransitionId;this.changedMembers=changedMembers;}
 /** Returns the completed transition.
  * @return completed transition identity */
 public UUID completedTransitionId(){return transitionId;}
 /** Returns transition size.
  * @return number of members changed by the transition */
 public int changedMemberCount(){return changedMembers;}
 public UUID clusterId(){return base.clusterId();}public long stateVersion(){return base.stateVersion();}public UUID configurationId(){return base.configurationId();}public byte[] previousHash(){return base.previousHash();}public List<ClusterMember> members(){return base.members();}public long creationEpochMillis(){return base.creationEpochMillis();}public Set<UUID> voters(){return base.voters();}public byte[] hash(){return base.hash();}
}
