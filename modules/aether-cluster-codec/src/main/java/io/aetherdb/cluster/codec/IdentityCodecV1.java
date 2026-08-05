package io.aetherdb.cluster.codec;

import io.aetherdb.cluster.api.*;
import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact fixed-size codecs for durable cluster and node identities. */
public final class IdentityCodecV1 {
    /** Encoded identity length in bytes. */
    public static final int BYTES=256;
    private IdentityCodecV1(){}
    /** Encodes a cluster identity.
     * @param v identity to encode
     * @return fixed-size bytes */
    public static byte[] encodeCluster(ClusterIdentity v){byte[] out=base("AETHCLI1");ByteBuffer b=le(out);b.position(16);putUuid(b,v.clusterId());b.putLong(v.creationEpochMillis()).putLong(1).putLong(1);putUuid(b,v.bootstrapConfigurationId());b.put(v.bootstrapConfigurationHash()).put(v.compatibilityFingerprint());finish(out);return out;}
    /** Decodes a cluster identity.
     * @param in fixed-size bytes
     * @return validated identity */
    public static ClusterIdentity decodeCluster(byte[] in){ByteBuffer b=validate(in,"AETHCLI1");b.position(16);UUID cluster=getUuid(b);long created=b.getLong();if(b.getLong()!=1||b.getLong()!=1)throw invalid();UUID config=getUuid(b);byte[] hash=get(b,32),fp=get(b,32);zero(in,136,252);return new ClusterIdentity(cluster,created,config,hash,fp);}
    /** Encodes a node identity.
     * @param v identity to encode
     * @return fixed-size bytes */
    public static byte[] encodeNode(NodeIdentity v){byte[] out=base("AETHNDI1");ByteBuffer b=le(out);b.position(16);putUuid(b,v.clusterId());putUuid(b,v.nodeId());b.putLong(v.creationEpochMillis()).put((byte)v.initialRole().code()).position(64);b.putLong(v.generation());b.put(nodeHash(v));finish(out);return out;}
    /** Decodes a node identity.
     * @param in fixed-size bytes
     * @return validated identity */
    public static NodeIdentity decodeNode(byte[] in){ByteBuffer b=validate(in,"AETHNDI1");b.position(16);UUID c=getUuid(b),n=getUuid(b);long created=b.getLong();MemberRole role=MemberRole.fromCode(b.get()&255);zero(in,57,64);b.position(64);long gen=b.getLong();byte[] stored=get(b,32);zero(in,104,252);NodeIdentity value=new NodeIdentity(c,n,created,role,gen);if(!MessageDigest.isEqual(stored,nodeHash(value)))throw invalid();return value;}
    private static byte[] base(String magic){byte[] out=new byte[BYTES];ByteBuffer b=le(out);b.put(magic.getBytes(StandardCharsets.US_ASCII)).putShort((short)1).putShort((short)BYTES).putInt(0);return out;}
    private static ByteBuffer validate(byte[] in,String magic){if(in==null||in.length!=BYTES||le(in).getInt(252)!=MaskedCrc32c.masked(in,0,252))throw invalid();ByteBuffer b=le(in);byte[] m=get(b,8);if(!Arrays.equals(m,magic.getBytes(StandardCharsets.US_ASCII))||b.getShort()!=1||b.getShort()!=BYTES||b.getInt()!=0)throw invalid();return b;}
    private static byte[] nodeHash(NodeIdentity v){try{MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN);putUuid(b,v.clusterId());putUuid(b,v.nodeId());b.put((byte)v.initialRole().code()).putLong(v.creationEpochMillis()).putLong(v.generation());return d.digest(b.array());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void finish(byte[] out){le(out).putInt(252,MaskedCrc32c.masked(out,0,252));}
    static ByteBuffer le(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);} static void putUuid(ByteBuffer b,UUID u){b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());} static UUID getUuid(ByteBuffer b){return new UUID(b.getLong(),b.getLong());} static byte[] get(ByteBuffer b,int n){byte[] v=new byte[n];b.get(v);return v;} static void zero(byte[] b,int from,int to){for(int i=from;i<to;i++)if(b[i]!=0)throw invalid();} static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid cluster identity encoding");}
}
