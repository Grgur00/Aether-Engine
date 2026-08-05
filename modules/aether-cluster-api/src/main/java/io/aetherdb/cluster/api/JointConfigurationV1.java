package io.aetherdb.cluster.api;
import java.util.*;
/** First durable representation of a joint-consensus configuration. */
public final class JointConfigurationV1 implements JointConfiguration{
 private final UUID transition,proposer;private final StableConfiguration old;private final StableConfigurationV2 target;private final long created;private final byte[] hash;
 /**
  * Creates a validated transition between two stable configurations.
  * @param transition transition identity
  * @param old outgoing configuration
  * @param target incoming configuration
  * @param proposer proposing node
  * @param created creation time in epoch milliseconds
  * @param hash canonical transition hash
  */
 public JointConfigurationV1(UUID transition,StableConfiguration old,StableConfigurationV2 target,UUID proposer,long created,byte[] hash){if(transition==null||transition.equals(new UUID(0,0))||old==null||target==null||proposer==null||proposer.equals(new UUID(0,0))||!old.clusterId().equals(target.clusterId())||target.stateVersion()!=old.stateVersion()+2||created<0||hash==null||hash.length!=32)throw new IllegalArgumentException("invalid joint configuration");Set<UUID>x=new HashSet<>(old.voters());x.retainAll(target.voters());if(x.isEmpty())throw new IllegalArgumentException("voter intersection must be nonempty");this.transition=transition;this.old=old;this.target=target;this.proposer=proposer;this.created=created;this.hash=hash.clone();}
 public UUID clusterId(){return old.clusterId();}public long stateVersion(){return old.stateVersion()+1;}public UUID transitionId(){return transition;}public StableConfiguration oldConfiguration(){return old;}public StableConfigurationV2 targetConfiguration(){return target;}public UUID proposerNodeId(){return proposer;}public long creationEpochMillis(){return created;}public byte[] hash(){return hash.clone();}
}
