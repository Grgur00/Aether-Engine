package io.aetherdb.cluster.api;
import java.util.UUID;
public record ClusterIdentity(UUID clusterId,long creationEpochMillis,UUID bootstrapConfigurationId,byte[] bootstrapConfigurationHash,byte[] compatibilityFingerprint){
 public ClusterIdentity{if(clusterId==null||clusterId.equals(new UUID(0,0))||bootstrapConfigurationId==null||bootstrapConfigurationId.equals(new UUID(0,0))||creationEpochMillis<0)throw new IllegalArgumentException("invalid cluster identity");if(bootstrapConfigurationHash==null||bootstrapConfigurationHash.length!=32||compatibilityFingerprint==null||compatibilityFingerprint.length!=32)throw new IllegalArgumentException("identity hashes must be 32 bytes");bootstrapConfigurationHash=bootstrapConfigurationHash.clone();compatibilityFingerprint=compatibilityFingerprint.clone();}
 @Override public byte[] bootstrapConfigurationHash(){return bootstrapConfigurationHash.clone();}@Override public byte[] compatibilityFingerprint(){return compatibilityFingerprint.clone();}
}
