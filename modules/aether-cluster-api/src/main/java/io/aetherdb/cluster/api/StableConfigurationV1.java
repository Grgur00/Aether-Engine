package io.aetherdb.cluster.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class StableConfigurationV1 implements StableConfiguration {
    private final UUID clusterId, configurationId; private final long version, created; private final byte[] previousHash, hash; private final List<ClusterMember> members; private final Set<UUID> voters;
    public StableConfigurationV1(UUID clusterId,long version,UUID configurationId,byte[] previousHash,List<ClusterMember> members,long created,byte[] hash){
        if(clusterId==null||clusterId.equals(new UUID(0,0))||configurationId==null||configurationId.equals(new UUID(0,0))||version<1||created<0) throw new IllegalArgumentException("invalid configuration identity");
        if(previousHash==null||previousHash.length!=32||hash==null||hash.length!=32) throw new IllegalArgumentException("hashes must be 32 bytes");
        this.members=members.stream().sorted((a,b)->compareUuid(a.nodeId(),b.nodeId())).toList();
        Set<UUID> ids=new HashSet<>(); Set<ClusterEndpoint> eps=new HashSet<>(); int vc=0,sc=0;
        for(ClusterMember m:this.members){if(!ids.add(m.nodeId()))throw new IllegalArgumentException("duplicate node"); for(var e:m.endpoints())if(!eps.add(e))throw new IllegalArgumentException("duplicate endpoint across nodes"); if(m.role()==MemberRole.VOTER)vc++;else sc++;}
        if(vc<1||vc>31||sc>64)throw new IllegalArgumentException("invalid member counts");
        this.clusterId=clusterId;this.version=version;this.configurationId=configurationId;this.previousHash=previousHash.clone();this.created=created;this.hash=hash.clone();this.voters=ids.stream().filter(id->this.members.stream().anyMatch(m->m.nodeId().equals(id)&&m.role()==MemberRole.VOTER)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public static int compareUuid(UUID a,UUID b){int c=Long.compareUnsigned(a.getMostSignificantBits(),b.getMostSignificantBits());return c!=0?c:Long.compareUnsigned(a.getLeastSignificantBits(),b.getLeastSignificantBits());}
    public UUID clusterId(){return clusterId;} public long stateVersion(){return version;} public UUID configurationId(){return configurationId;} public byte[] previousHash(){return previousHash.clone();} public List<ClusterMember> members(){return members;} public long creationEpochMillis(){return created;} public Set<UUID> voters(){return voters;} public byte[] hash(){return hash.clone();}
}
